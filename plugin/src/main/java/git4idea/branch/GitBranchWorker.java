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

import java.util.Collection;
import java.util.List;

import jakarta.annotation.Nonnull;

import consulo.application.ApplicationManager;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.ex.awt.Messages;
import consulo.util.lang.Couple;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.Change;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.function.Condition;
import git4idea.GitCommit;
import git4idea.GitExecutionException;
import git4idea.GitLocalBranch;
import git4idea.changes.GitChangeUtils;
import git4idea.commands.Git;
import git4idea.history.GitHistoryUtils;
import git4idea.rebase.GitRebaseUtils;
import git4idea.repo.GitRepository;
import git4idea.ui.branch.GitCompareBranchesDialog;
import git4idea.util.GitCommitCompareInfo;

/**
 * Executes the logic of git branch operations.
 * All operations are run in the current thread.
 * All UI interaction is done via the {@link GitBranchUiHandler} passed to the constructor.
 */
public final class GitBranchWorker
{

	private static final Logger LOG = Logger.getInstance(GitBranchWorker.class);

	@Nonnull
	private final Project myProject;
	@Nonnull
	private final Git myGit;
	@Nonnull
	private final GitBranchUiHandler myUiHandler;

	public GitBranchWorker(@Nonnull Project project, @Nonnull Git git, @Nonnull GitBranchUiHandler uiHandler)
	{
		myProject = project;
		myGit = git;
		myUiHandler = uiHandler;
	}

	public void checkoutNewBranch(@Nonnull final String name, @Nonnull List<GitRepository> repositories)
	{
		updateInfo(repositories);
		repositories = ContainerUtil.filter(repositories, new Condition<GitRepository>()
		{
			@Override
			public boolean value(GitRepository repository)
			{
				GitLocalBranch currentBranch = repository.getCurrentBranch();
				return currentBranch == null || !currentBranch.getName().equals(name);
			}
		});
		if(!repositories.isEmpty())
		{
			new GitCheckoutNewBranchOperation(myProject, myGit, myUiHandler, repositories, name).execute();
		}
		else
		{
			LOG.error("Creating new branch the same as current in all repositories: " + name);
		}
	}

	public void createNewTag(@Nonnull final String name, @Nonnull final String reference, @Nonnull final List<GitRepository> repositories)
	{
		for(GitRepository repository : repositories)
		{
			myGit.createNewTag(repository, name, null, reference);
			repository.getRepositoryFiles().refresh();
		}
	}

	public void checkoutNewBranchStartingFrom(@Nonnull String newBranchName, @Nonnull String startPoint, @Nonnull List<GitRepository> repositories)
	{
		updateInfo(repositories);
		new GitCheckoutOperation(myProject, myGit, myUiHandler, repositories, startPoint, false, true, newBranchName).execute();
	}

	public void checkout(@Nonnull final String reference, boolean detach, @Nonnull List<GitRepository> repositories)
	{
		updateInfo(repositories);
		new GitCheckoutOperation(myProject, myGit, myUiHandler, repositories, reference, detach, false, null).execute();
	}


	public void deleteBranch(@Nonnull final String branchName, @Nonnull final List<GitRepository> repositories)
	{
		updateInfo(repositories);
		new GitDeleteBranchOperation(myProject, myGit, myUiHandler, repositories, branchName).execute();
	}

	public void deleteRemoteBranch(@Nonnull final String branchName, @Nonnull final List<GitRepository> repositories)
	{
		updateInfo(repositories);
		new GitDeleteRemoteBranchOperation(myProject, myGit, myUiHandler, repositories, branchName).execute();
	}

	public void merge(@Nonnull final String branchName, @Nonnull final GitBrancher.DeleteOnMergeOption deleteOnMerge, @Nonnull final List<GitRepository> repositories)
	{
		updateInfo(repositories);
		new GitMergeOperation(myProject, myGit, myUiHandler, repositories, branchName, deleteOnMerge).execute();
	}

	public void rebase(@Nonnull List<GitRepository> repositories, @Nonnull String branchName)
	{
		updateInfo(repositories);
		GitRebaseUtils.rebase(myProject, repositories, new GitRebaseParams(branchName), myUiHandler.getProgressIndicator());
	}

	public void rebaseOnCurrent(@Nonnull List<GitRepository> repositories, @Nonnull String branchName)
	{
		updateInfo(repositories);
		GitRebaseUtils.rebase(myProject, repositories, new GitRebaseParams(branchName, null, "HEAD", false, false), myUiHandler.getProgressIndicator());
	}

	public void renameBranch(@Nonnull String currentName, @Nonnull String newName, @Nonnull List<GitRepository> repositories)
	{
		updateInfo(repositories);
		new GitRenameBranchOperation(myProject, myGit, myUiHandler, currentName, newName, repositories).execute();
	}

	public void compare(@Nonnull final String branchName, @Nonnull final List<GitRepository> repositories, @Nonnull final GitRepository selectedRepository)
	{
		final GitCommitCompareInfo myCompareInfo = loadCommitsToCompare(repositories, branchName);
		if(myCompareInfo == null)
		{
			LOG.error("The task to get compare info didn't finish. Repositories: \n" + repositories + "\nbranch name: " + branchName);
			return;
		}
		ApplicationManager.getApplication().invokeLater(new Runnable()
		{
			@Override
			public void run()
			{
				displayCompareDialog(branchName, GitBranchUtil.getCurrentBranchOrRev(repositories), myCompareInfo, selectedRepository);
			}
		});
	}

	private GitCommitCompareInfo loadCommitsToCompare(List<GitRepository> repositories, String branchName)
	{
		GitCommitCompareInfo compareInfo = new GitCommitCompareInfo();
		for(GitRepository repository : repositories)
		{
			compareInfo.put(repository, loadCommitsToCompare(repository, branchName));
			compareInfo.put(repository, loadTotalDiff(repository, branchName));
		}
		return compareInfo;
	}

	@Nonnull
	private static Collection<Change> loadTotalDiff(@Nonnull GitRepository repository, @Nonnull String branchName)
	{
		try
		{
			// return git diff between current working directory and branchName: working dir should be displayed as a 'left' one (base)
			return GitChangeUtils.getDiffWithWorkingDir(repository.getProject(), repository.getRoot(), branchName, null, true);
		}
		catch(VcsException e)
		{
			// we treat it as critical and report an error
			throw new GitExecutionException("Couldn't get [git diff " + branchName + "] on repository [" + repository.getRoot() + "]", e);
		}
	}

	@Nonnull
	private Couple<List<GitCommit>> loadCommitsToCompare(@Nonnull GitRepository repository, @Nonnull final String branchName)
	{
		final List<GitCommit> headToBranch;
		final List<GitCommit> branchToHead;
		try
		{
			headToBranch = GitHistoryUtils.history(myProject, repository.getRoot(), ".." + branchName);
			branchToHead = GitHistoryUtils.history(myProject, repository.getRoot(), branchName + "..");
		}
		catch(VcsException e)
		{
			// we treat it as critical and report an error
			throw new GitExecutionException("Couldn't get [git log .." + branchName + "] on repository [" + repository.getRoot() + "]", e);
		}
		return Couple.of(headToBranch, branchToHead);
	}

	private void displayCompareDialog(@Nonnull String branchName, @Nonnull String currentBranch, @Nonnull GitCommitCompareInfo compareInfo, @Nonnull GitRepository selectedRepository)
	{
		if(compareInfo.isEmpty())
		{
			Messages.showInfoMessage(myProject, String.format("<html>There are no changes between <code>%s</code> and <code>%s</code></html>", currentBranch, branchName), "No Changes Detected");
		}
		else
		{
			new GitCompareBranchesDialog(myProject, branchName, currentBranch, compareInfo, selectedRepository).show();
		}
	}

	private static void updateInfo(@Nonnull Collection<GitRepository> repositories)
	{
		for(GitRepository repository : repositories)
		{
			repository.update();
		}
	}

}
