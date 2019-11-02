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
package git4idea.push;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import com.intellij.dvcs.push.OutgoingCommitsProvider;
import com.intellij.dvcs.push.OutgoingResult;
import com.intellij.dvcs.push.PushSpec;
import com.intellij.dvcs.push.VcsError;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.vcs.log.VcsFullCommitDetails;
import git4idea.GitCommit;
import git4idea.GitUtil;
import git4idea.history.GitHistoryUtils;
import git4idea.repo.GitRepository;

public class GitOutgoingCommitsProvider extends OutgoingCommitsProvider<GitRepository, GitPushSource, GitPushTarget>
{

	@Nonnull
	private final Project myProject;

	public GitOutgoingCommitsProvider(@Nonnull Project project)
	{
		myProject = project;
	}

	@Nonnull
	@Override
	public OutgoingResult getOutgoingCommits(@Nonnull GitRepository repository,
			@Nonnull PushSpec<GitPushSource, GitPushTarget> pushSpec,
			boolean initial)
	{
		String source = pushSpec.getSource().getBranch().getFullName();
		GitPushTarget target = pushSpec.getTarget();
		String destination = target.getBranch().getFullName();
		try
		{
			List<GitCommit> commits;
			if(!target.isNewBranchCreated())
			{
				commits = GitHistoryUtils.history(myProject, repository.getRoot(), destination + ".." + source);
			}
			else
			{
				commits = GitHistoryUtils.history(myProject, repository.getRoot(), source, "--not", "--remotes=" + target.getBranch().getRemote()
						.getName(), "--max-count=" + 1000);
			}
			return new OutgoingResult(commits, Collections.<VcsError>emptyList());
		}
		catch(VcsException e)
		{
			return new OutgoingResult(Collections.<VcsFullCommitDetails>emptyList(), Collections.singletonList(new VcsError(GitUtil
					.cleanupErrorPrefixes(e.getMessage()))));
		}
	}
}
