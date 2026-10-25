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
package git4idea.changes;

import git4idea.GitVcs;
import git4idea.GitCommit;
import consulo.versionControlSystem.log.util.VcsUserUtil;
import consulo.versionControlSystem.log.VcsFullCommitDetails;
import consulo.versionControlSystem.util.VcsUtil;
import git4idea.repo.GitRepositoryManager;
import git4idea.history.GitFileHistory;
import consulo.application.util.function.AsynchConsumer;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.io.FileUtil;
import consulo.util.lang.Pair;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.*;
import consulo.versionControlSystem.action.VcsContextFactory;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangesUtil;
import consulo.versionControlSystem.change.commited.DecoratorManager;
import consulo.versionControlSystem.change.commited.VcsCommittedListsZipper;
import consulo.versionControlSystem.change.commited.VcsCommittedViewAuxiliary;
import consulo.versionControlSystem.history.VcsFileRevision;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.versionBrowser.ChangeBrowserSettings;
import consulo.versionControlSystem.versionBrowser.CommittedChangeList;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.*;
import git4idea.history.GitHistoryUtils;
import git4idea.history.browser.GitHeavyCommit;
import git4idea.history.browser.SymbolicRefs;
import git4idea.repo.GitRepository;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

/**
 * The provider for committed change lists
 */
public class GitCommittedChangeListProvider implements CommittedChangesProvider<CommittedChangeList, ChangeBrowserSettings>
{
	private static final Logger LOG = Logger.getInstance(GitCommittedChangeListProvider.class);

	@Nonnull
	private final Project myProject;

	public GitCommittedChangeListProvider(@Nonnull Project project)
	{
		myProject = project;
	}

	@Override
	@Nonnull
	public ChangeBrowserSettings createDefaultSettings()
	{
		return new ChangeBrowserSettings();
	}

	@Override
	public RepositoryLocation getLocationFor(@Nonnull FilePath root)
	{
		VirtualFile gitRoot = GitUtil.getGitRootOrNull(root);
		if(gitRoot == null)
		{
			return null;
		}
		GitRepository repository = GitUtil.getRepositoryManager(myProject).getRepositoryForRoot(gitRoot);
		if(repository == null)
		{
			LOG.info("No GitRepository for " + gitRoot);
			return null;
		}
		GitLocalBranch currentBranch = repository.getCurrentBranch();
		if(currentBranch == null)
		{
			return null;
		}
		GitRemoteBranch trackedBranch = currentBranch.findTrackedBranch(repository);
		if(trackedBranch == null)
		{
			return null;
		}
		File rootFile = new File(gitRoot.getPath());
		return new GitRepositoryLocation(trackedBranch.getRemote().getFirstUrl(), rootFile);
	}

	@Override
	public RepositoryLocation getLocationFor(FilePath root, String repositoryPath)
	{
		return getLocationFor(root);
	}

	@Override
	@Nullable
	public VcsCommittedListsZipper getZipper()
	{
		return null;
	}

	@Override
	public void loadCommittedChanges(ChangeBrowserSettings settings,
									 RepositoryLocation location,
									 int maxCount,
									 final AsynchConsumer<CommittedChangeList> consumer) throws VcsException
	{
		try
		{
			getCommittedChangesImpl(settings, location, maxCount, gitCommittedChangeList -> consumer.accept(gitCommittedChangeList));
		}
		finally
		{
			consumer.finished();
		}
	}

	@Override
	public List<CommittedChangeList> getCommittedChanges(ChangeBrowserSettings settings,
														 RepositoryLocation location,
														 final int maxCount) throws VcsException
	{

		final List<CommittedChangeList> result = new ArrayList<CommittedChangeList>();

		getCommittedChangesImpl(settings, location, maxCount, committedChangeList -> result.add(committedChangeList));

		return result;
	}

	private void getCommittedChangesImpl(ChangeBrowserSettings settings,
                                         RepositoryLocation location,
                                         final int maxCount,
                                         final Consumer<GitCommittedChangeList> consumer) throws VcsException
	{
		GitRepositoryLocation l = (GitRepositoryLocation) location;
		final Long beforeRev = settings.getChangeBeforeFilter();
		final Long afterRev = settings.getChangeAfterFilter();
		final Date beforeDate = settings.getDateBeforeFilter();
		final Date afterDate = settings.getDateAfterFilter();
		final String author = settings.getUserFilter();
		VirtualFile root = LocalFileSystem.getInstance().findFileByIoFile(l.getRoot());
		if(root == null)
		{
			throw new VcsException("The repository does not exists anymore: " + l.getRoot());
		}

		GitUtil.getLocalCommittedChanges(myProject, root, h ->
		{
			if(!StringUtil.isEmpty(author))
			{
				h.addParameters("--author=" + author);
			}
			if(beforeDate != null)
			{
				h.addParameters("--before=" + GitUtil.gitTime(beforeDate));
			}
			if(afterDate != null)
			{
				h.addParameters("--after=" + GitUtil.gitTime(afterDate));
			}
			if(maxCount != getUnlimitedCountValue())
			{
				h.addParameters("-n" + maxCount);
			}
			if(beforeRev != null && afterRev != null)
			{
				h.addParameters(GitUtil.formatLongRev(afterRev) + ".." + GitUtil.formatLongRev(beforeRev));
			}
			else if(beforeRev != null)
			{
				h.addParameters(GitUtil.formatLongRev(beforeRev));
			}
			else if(afterRev != null)
			{
				h.addParameters(GitUtil.formatLongRev(afterRev) + "..");
			}
		}, consumer, false);
	}

	@Override
	public ChangeListColumn[] getColumns()
	{
		return new ChangeListColumn[]{
				ChangeListColumn.NUMBER,
				ChangeListColumn.DATE,
				ChangeListColumn.DESCRIPTION,
				ChangeListColumn.NAME
		};
	}

	@Override
	public VcsCommittedViewAuxiliary createActions(DecoratorManager manager, RepositoryLocation location)
	{
		return null;
	}

	@Override
	public int getUnlimitedCountValue()
	{
		return -1;
	}

	@Override
	public Pair<CommittedChangeList, FilePath> getOneList(final VirtualFile file, final VcsRevisionNumber number) throws VcsException
	{
		final FilePath filePath = VcsContextFactory.getInstance().createFilePathOn(file);
		if(!(number instanceof GitRevisionNumber))
		{
			LOG.error("Unsupported revision number: " + number);
			return null;
		}

		GitRepository repository = GitRepositoryManager.getInstance(myProject).getRepositoryForFile(VcsUtil.getLastCommitPath(myProject, filePath));
		if(repository == null)
		{
			return null;
		}
		VirtualFile root = repository.getRoot();

		VcsFullCommitDetails gitCommit = getCommitDetails(myProject, root, number);
		if(gitCommit == null)
		{
			return null;
		}

		CommittedChangeList commit = createCommittedChangeList(myProject, gitCommit, (GitRevisionNumber) number);

		final Collection<Change> changes = commit.getChanges();
		if(changes.size() == 1)
		{
			Change change = changes.iterator().next();
			return Pair.create(commit, ChangesUtil.getFilePath(change));
		}
		for(Change change : changes)
		{
			if(change.getAfterRevision() != null && FileUtil.filesEqual(filePath.getIOFile(), change.getAfterRevision().getFile().getIOFile()))
			{
				return new Pair<CommittedChangeList, FilePath>(commit, filePath);
			}
		}
		final String afterTime = "--after=" + GitUtil.gitTime(new Date(gitCommit.getCommitTime()));
		final List<VcsFileRevision> history = GitFileHistory.collectHistory(myProject, filePath, afterTime);
		if(history.isEmpty())
		{
			return new Pair<CommittedChangeList, FilePath>(commit, filePath);
		}
		return Pair.create(commit, ((GitFileRevision) history.get(history.size() - 1)).getPath());
	}

	@Override
	public RepositoryLocation getForNonLocal(VirtualFile file)
	{
		return null;
	}

	@Override
	public boolean supportsIncomingChanges()
	{
		return false;
	}

	@Nullable
	private static VcsFullCommitDetails getCommitDetails(@Nonnull Project project,
			@Nonnull VirtualFile root,
			@Nonnull VcsRevisionNumber number) throws VcsException
	{
		String[] hashParameters = GitHistoryUtils.formHashParameters(project, Collections.singleton(number.asString()));
		List<GitCommit> gitCommits = GitHistoryUtils.history(project, root, hashParameters);
		if(gitCommits.size() != 1)
		{
			return null;
		}

		return gitCommits.get(0);
	}

	@Nonnull
	private static GitCommittedChangeList createCommittedChangeList(@Nonnull Project project,
			@Nonnull VcsFullCommitDetails gitCommit,
			@Nonnull GitRevisionNumber revisionNumber)
	{
		return new GitCommittedChangeList(gitCommit.getFullMessage() + " (" + gitCommit.getId().toShortString() + ")",
				gitCommit.getFullMessage(), VcsUserUtil.toExactString(gitCommit.getAuthor()),
				revisionNumber,
				new Date(gitCommit.getAuthorTime()), gitCommit.getChanges(),
				GitVcs.getInstance(project), true);
	}

	@Nullable
	public static GitCommittedChangeList getCommittedChangeList(@Nonnull Project project,
			@Nonnull VirtualFile root,
			@Nonnull GitRevisionNumber revisionNumber) throws VcsException
	{
		VcsFullCommitDetails details = getCommitDetails(project, root, revisionNumber);
		if(details == null)
		{
			return null;
		}

		return createCommittedChangeList(project, details, revisionNumber);
	}
}
