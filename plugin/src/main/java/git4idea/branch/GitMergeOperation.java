/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.branch;

import consulo.application.AccessToken;
import consulo.application.util.function.Computable;
import consulo.ide.ServiceManager;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.event.NotificationListener;
import consulo.ui.ex.awt.UIUtil;
import consulo.util.lang.Pair;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangeListManager;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.commands.*;
import git4idea.config.GitVcsSettings;
import git4idea.merge.GitMergeCommittingConflictResolver;
import git4idea.merge.GitMerger;
import git4idea.repo.GitRepository;
import git4idea.reset.GitResetMode;
import git4idea.util.GitPreservingProcess;

import jakarta.annotation.Nonnull;
import javax.swing.event.HyperlinkEvent;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

class GitMergeOperation extends GitBranchOperation
{

	private static final Logger LOG = Logger.getInstance(GitMergeOperation.class);
	public static final String ROLLBACK_PROPOSAL = "You may rollback (reset to the commit before merging) not to let branches diverge.";

	@Nonnull
	private final ChangeListManager myChangeListManager;
	@Nonnull
	private final String myBranchToMerge;
	private final GitBrancher.DeleteOnMergeOption myDeleteOnMerge;

	// true in value, if we've stashed local changes before merge and will need to unstash after resolving conflicts.
	@Nonnull
	private final Map<GitRepository, Boolean> myConflictedRepositories = new HashMap<>();
	private GitPreservingProcess myPreservingProcess;

	GitMergeOperation(@Nonnull Project project,
			@Nonnull Git git,
			@Nonnull GitBranchUiHandler uiHandler,
			@Nonnull Collection<GitRepository> repositories,
			@Nonnull String branchToMerge,
			GitBrancher.DeleteOnMergeOption deleteOnMerge)
	{
		super(project, git, uiHandler, repositories);
		myBranchToMerge = branchToMerge;
		myDeleteOnMerge = deleteOnMerge;
		myChangeListManager = ChangeListManager.getInstance(myProject);
	}

	@Override
	protected void execute()
	{
		LOG.info("starting");
		saveAllDocuments();
		boolean fatalErrorHappened = false;
		int alreadyUpToDateRepositories = 0;
		try(AccessToken token = DvcsUtil.workingTreeChangeStarted(myProject, getOperationName()))
		{
			while(hasMoreRepositories() && !fatalErrorHappened)
			{
				final GitRepository repository = next();
				LOG.info("next repository: " + repository);

				VirtualFile root = repository.getRoot();
				GitLocalChangesWouldBeOverwrittenDetector localChangesDetector = new GitLocalChangesWouldBeOverwrittenDetector(root, GitLocalChangesWouldBeOverwrittenDetector.Operation.MERGE);
				GitSimpleEventDetector unmergedFiles = new GitSimpleEventDetector(GitSimpleEventDetector.Event.UNMERGED_PREVENTING_MERGE);
				GitUntrackedFilesOverwrittenByOperationDetector untrackedOverwrittenByMerge = new GitUntrackedFilesOverwrittenByOperationDetector(root);
				GitSimpleEventDetector mergeConflict = new GitSimpleEventDetector(GitSimpleEventDetector.Event.MERGE_CONFLICT);
				GitSimpleEventDetector alreadyUpToDateDetector = new GitSimpleEventDetector(GitSimpleEventDetector.Event.ALREADY_UP_TO_DATE);

				GitCommandResult result = myGit.merge(repository, myBranchToMerge, Collections.<String>emptyList(), localChangesDetector, unmergedFiles, untrackedOverwrittenByMerge, mergeConflict,
						alreadyUpToDateDetector);
				if(result.success())
				{
					LOG.info("Merged successfully");
					refresh(repository);
					markSuccessful(repository);
					if(alreadyUpToDateDetector.hasHappened())
					{
						alreadyUpToDateRepositories += 1;
					}
				}
				else if(unmergedFiles.hasHappened())
				{
					LOG.info("Unmerged files error!");
					fatalUnmergedFilesError();
					fatalErrorHappened = true;
				}
				else if(localChangesDetector.wasMessageDetected())
				{
					LOG.info("Local changes would be overwritten by merge!");
					boolean smartMergeSucceeded = proposeSmartMergePerformAndNotify(repository, localChangesDetector);
					if(!smartMergeSucceeded)
					{
						fatalErrorHappened = true;
					}
				}
				else if(mergeConflict.hasHappened())
				{
					LOG.info("Merge conflict");
					myConflictedRepositories.put(repository, Boolean.FALSE);
					refresh(repository);
					markSuccessful(repository);
				}
				else if(untrackedOverwrittenByMerge.wasMessageDetected())
				{
					LOG.info("Untracked files would be overwritten by merge!");
					fatalUntrackedFilesError(repository.getRoot(), untrackedOverwrittenByMerge.getRelativeFilePaths());
					fatalErrorHappened = true;
				}
				else
				{
					LOG.info("Unknown error. " + result);
					fatalError(getCommonErrorTitle(), result.getErrorOutputAsJoinedString());
					fatalErrorHappened = true;
				}
			}

			if(fatalErrorHappened)
			{
				notifyAboutRemainingConflicts();
			}
			else
			{
				boolean allConflictsResolved = resolveConflicts();
				if(allConflictsResolved)
				{
					if(alreadyUpToDateRepositories < getRepositories().size())
					{
						notifySuccess();
					}
					else
					{
						notifySuccess("Already up-to-date");
					}
				}
			}

			restoreLocalChanges();
		}
	}

	private void notifyAboutRemainingConflicts()
	{
		if(!myConflictedRepositories.isEmpty())
		{
			new MyMergeConflictResolver().notifyUnresolvedRemain();
		}
	}

	@Override
	protected void notifySuccess(@Nonnull String message)
	{
		switch(myDeleteOnMerge)
		{
			case DELETE:
				super.notifySuccess(message);
				UIUtil.invokeLaterIfNeeded(new Runnable()
				{ // bg process needs to be started from the EDT
					@Override
					public void run()
					{
						GitBrancher brancher = ServiceManager.getService(myProject, GitBrancher.class);
						brancher.deleteBranch(myBranchToMerge, new ArrayList<>(getRepositories()));
					}
				});
				break;
			case PROPOSE:
				String description = message + "<br/><a href='delete'>Delete " + myBranchToMerge + "</a>";
				VcsNotifier.getInstance(myProject).notifySuccess("", description, new DeleteMergedLocalBranchNotificationListener());
				break;
			case NOTHING:
				super.notifySuccess(message);
				break;
		}
	}

	private boolean resolveConflicts()
	{
		if(!myConflictedRepositories.isEmpty())
		{
			return new MyMergeConflictResolver().merge();
		}
		return true;
	}

	private boolean proposeSmartMergePerformAndNotify(@Nonnull GitRepository repository, @Nonnull GitMessageWithFilesDetector localChangesOverwrittenByMerge)
	{
		Pair<List<GitRepository>, List<Change>> conflictingRepositoriesAndAffectedChanges = getConflictingRepositoriesAndAffectedChanges(repository, localChangesOverwrittenByMerge,
				myCurrentHeads.get(repository), myBranchToMerge);
		List<GitRepository> allConflictingRepositories = conflictingRepositoriesAndAffectedChanges.getFirst();
		List<Change> affectedChanges = conflictingRepositoriesAndAffectedChanges.getSecond();

		Collection<String> absolutePaths = GitUtil.toAbsolute(repository.getRoot(), localChangesOverwrittenByMerge.getRelativeFilePaths());
		int smartCheckoutDecision = myUiHandler.showSmartOperationDialog(myProject, affectedChanges, absolutePaths, "merge", null);
		if(smartCheckoutDecision == GitSmartOperationDialog.SMART_EXIT_CODE)
		{
			return doSmartMerge(allConflictingRepositories);
		}
		else
		{
			fatalLocalChangesError(myBranchToMerge);
			return false;
		}
	}

	private void restoreLocalChanges()
	{
		if(myPreservingProcess != null)
		{
			myPreservingProcess.load();
		}
	}

	private boolean doSmartMerge(@Nonnull final Collection<GitRepository> repositories)
	{
		final AtomicBoolean success = new AtomicBoolean();
		myPreservingProcess = new GitPreservingProcess(myProject, myGit, GitUtil.getRootsFromRepositories(repositories), "merge", myBranchToMerge, GitVcsSettings.UpdateChangesPolicy.STASH,
				getIndicator(), new Runnable()
		{
			@Override
			public void run()
			{
				success.set(doMerge(repositories));
			}
		});
		myPreservingProcess.execute(new Computable<Boolean>()
		{
			@Override
			public Boolean compute()
			{
				return myConflictedRepositories.isEmpty();
			}
		});
		return success.get();
	}

	/**
	 * Performs merge in the given repositories.
	 * Handle only merge conflict situation: all other cases should have been handled before and are treated as errors.
	 * Conflict is treated as a success: the repository with conflict is remembered and will be handled later along with all other conflicts.
	 * If an error happens in one repository, the method doesn't go further in others, and shows a notification.
	 *
	 * @return true if merge has succeeded without errors (but possibly with conflicts) in all repositories;
	 * false if it failed at least in one of them.
	 */
	private boolean doMerge(@Nonnull Collection<GitRepository> repositories)
	{
		for(GitRepository repository : repositories)
		{
			GitSimpleEventDetector mergeConflict = new GitSimpleEventDetector(GitSimpleEventDetector.Event.MERGE_CONFLICT);
			GitCommandResult result = myGit.merge(repository, myBranchToMerge, Collections.<String>emptyList(), mergeConflict);
			if(!result.success())
			{
				if(mergeConflict.hasHappened())
				{
					myConflictedRepositories.put(repository, Boolean.TRUE);
					refresh(repository);
					markSuccessful(repository);
				}
				else
				{
					fatalError(getCommonErrorTitle(), result.getErrorOutputAsJoinedString());
					return false;
				}
			}
			else
			{
				refresh(repository);
				markSuccessful(repository);
			}
		}
		return true;
	}

	@Nonnull
	private String getCommonErrorTitle()
	{
		return "Couldn't merge " + myBranchToMerge;
	}

	@Override
	protected void rollback()
	{
		LOG.info("starting rollback...");
		Collection<GitRepository> repositoriesForSmartRollback = new ArrayList<>();
		Collection<GitRepository> repositoriesForSimpleRollback = new ArrayList<>();
		Collection<GitRepository> repositoriesForMergeRollback = new ArrayList<>();
		for(GitRepository repository : getSuccessfulRepositories())
		{
			if(myConflictedRepositories.containsKey(repository))
			{
				repositoriesForMergeRollback.add(repository);
			}
			else if(thereAreLocalChangesIn(repository))
			{
				repositoriesForSmartRollback.add(repository);
			}
			else
			{
				repositoriesForSimpleRollback.add(repository);
			}
		}

		LOG.info("for smart rollback: " + DvcsUtil.getShortNames(repositoriesForSmartRollback) +
				"; for simple rollback: " + DvcsUtil.getShortNames(repositoriesForSimpleRollback) +
				"; for merge rollback: " + DvcsUtil.getShortNames(repositoriesForMergeRollback));

		GitCompoundResult result = smartRollback(repositoriesForSmartRollback);
		for(GitRepository repository : repositoriesForSimpleRollback)
		{
			result.append(repository, rollback(repository));
		}
		for(GitRepository repository : repositoriesForMergeRollback)
		{
			result.append(repository, rollbackMerge(repository));
		}
		myConflictedRepositories.clear();

		if(!result.totalSuccess())
		{
			VcsNotifier.getInstance(myProject).notifyError("Error during rollback", result.getErrorOutputWithReposIndication());
		}
		LOG.info("rollback finished.");
	}

	@Nonnull
	private GitCompoundResult smartRollback(@Nonnull final Collection<GitRepository> repositories)
	{
		LOG.info("Starting smart rollback...");
		final GitCompoundResult result = new GitCompoundResult(myProject);
		Collection<VirtualFile> roots = GitUtil.getRootsFromRepositories(repositories);
		GitPreservingProcess preservingProcess = new GitPreservingProcess(myProject, myGit, roots, "merge", myBranchToMerge, GitVcsSettings.UpdateChangesPolicy.STASH, getIndicator(), new Runnable()
		{
			@Override
			public void run()
			{
				for(GitRepository repository : repositories)
				{
					result.append(repository, rollback(repository));
				}
			}
		});
		preservingProcess.execute();
		LOG.info("Smart rollback completed.");
		return result;
	}

	@Nonnull
	private GitCommandResult rollback(@Nonnull GitRepository repository)
	{
		return myGit.reset(repository, GitResetMode.HARD, getInitialRevision(repository));
	}

	@Nonnull
	private GitCommandResult rollbackMerge(@Nonnull GitRepository repository)
	{
		GitCommandResult result = myGit.resetMerge(repository, null);
		refresh(repository);
		return result;
	}

	private boolean thereAreLocalChangesIn(@Nonnull GitRepository repository)
	{
		return !myChangeListManager.getChangesIn(repository.getRoot()).isEmpty();
	}

	@Nonnull
	@Override
	public String getSuccessMessage()
	{
		return String.format("Merged <b><code>%s</code></b> to <b><code>%s</code></b>", myBranchToMerge, stringifyBranchesByRepos(myCurrentHeads));
	}

	@Nonnull
	@Override
	protected String getRollbackProposal()
	{
		return "However merge has succeeded for the following " + repositories() + ":<br/>" +
				successfulRepositoriesJoined() +
				"<br/>" + ROLLBACK_PROPOSAL;
	}

	@Nonnull
	@Override
	protected String getOperationName()
	{
		return "merge";
	}

	private void refresh(GitRepository... repositories)
	{
		for(GitRepository repository : repositories)
		{
			refreshRoot(repository);
			repository.update();
		}
	}

	private class MyMergeConflictResolver extends GitMergeCommittingConflictResolver
	{
		public MyMergeConflictResolver()
		{
			super(GitMergeOperation.this.myProject, myGit, new GitMerger(GitMergeOperation.this.myProject), GitUtil.getRootsFromRepositories(GitMergeOperation.this.myConflictedRepositories.keySet())
					, new Params(), true);
		}

		@Override
		protected void notifyUnresolvedRemain()
		{
			VcsNotifier.getInstance(myProject).notifyImportantWarning("Merged branch " + myBranchToMerge + " with conflicts", "Unresolved conflicts remain in the project. <a href='resolve'>Resolve " +
					"now.</a>", getResolveLinkListener());
		}
	}

	private class DeleteMergedLocalBranchNotificationListener implements NotificationListener
	{
		@Override
		public void hyperlinkUpdate(@Nonnull Notification notification, @Nonnull HyperlinkEvent event)
		{
			if(event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getDescription().equalsIgnoreCase("delete"))
			{
				GitBrancher brancher = ServiceManager.getService(myProject, GitBrancher.class);
				brancher.deleteBranch(myBranchToMerge, new ArrayList<>(getRepositories()));
			}
		}
	}
}
