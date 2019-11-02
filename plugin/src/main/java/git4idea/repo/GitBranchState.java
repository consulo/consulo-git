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
package git4idea.repo;

import java.util.Map;

import javax.annotation.Nonnull;

import com.intellij.dvcs.repo.Repository;
import com.intellij.vcs.log.Hash;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;

import javax.annotation.Nullable;

class GitBranchState
{
	@Nullable
	private final String currentRevision;
	@Nullable
	private final GitLocalBranch currentBranch;
	@Nonnull
	private final Repository.State state;
	@Nonnull
	private final Map<GitLocalBranch, Hash> localBranches;
	@Nonnull
	private final Map<GitRemoteBranch, Hash> remoteBranches;

	GitBranchState(@Nullable String currentRevision,
			@Nullable GitLocalBranch currentBranch,
			@Nonnull Repository.State state,
			@Nonnull Map<GitLocalBranch, Hash> localBranches,
			@Nonnull Map<GitRemoteBranch, Hash> remoteBranches)
	{
		this.currentRevision = currentRevision;
		this.currentBranch = currentBranch;
		this.state = state;
		this.localBranches = localBranches;
		this.remoteBranches = remoteBranches;
	}

	@Nullable
	public String getCurrentRevision()
	{
		return currentRevision;
	}

	@Nullable
	public GitLocalBranch getCurrentBranch()
	{
		return currentBranch;
	}

	@Nonnull
	public Repository.State getState()
	{
		return state;
	}

	@Nonnull
	public Map<GitLocalBranch, Hash> getLocalBranches()
	{
		return localBranches;
	}

	@Nonnull
	public Map<GitRemoteBranch, Hash> getRemoteBranches()
	{
		return remoteBranches;
	}
}
