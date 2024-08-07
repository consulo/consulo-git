/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package git4idea;

import consulo.versionControlSystem.log.Hash;
import git4idea.branch.GitBranchUtil;
import git4idea.repo.GitRemote;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public class GitStandardRemoteBranch extends GitRemoteBranch
{

	@Nonnull
	private final GitRemote myRemote;
	@Nonnull
	private final String myNameAtRemote;

	@Deprecated
	public GitStandardRemoteBranch(@Nonnull GitRemote remote, @Nonnull String nameAtRemote, @Nullable Hash hash)
	{
		this(remote, nameAtRemote);
	}

	public GitStandardRemoteBranch(@Nonnull GitRemote remote, @Nonnull String nameAtRemote)
	{
		super(formStandardName(remote, GitBranchUtil.stripRefsPrefix(nameAtRemote)));
		myRemote = remote;
		myNameAtRemote = GitBranchUtil.stripRefsPrefix(nameAtRemote);
	}

	@Nonnull
	private static String formStandardName(@Nonnull GitRemote remote, @Nonnull String nameAtRemote)
	{
		return remote.getName() + "/" + nameAtRemote;
	}

	@Override
	@Nonnull
	public GitRemote getRemote()
	{
		return myRemote;
	}

	@Override
	public boolean equals(Object o)
	{
		if(this == o)
		{
			return true;
		}
		if(o == null || getClass() != o.getClass())
		{
			return false;
		}
		if(!super.equals(o))
		{
			return false;
		}

		GitStandardRemoteBranch branch = (GitStandardRemoteBranch) o;

		if(!myNameAtRemote.equals(branch.myNameAtRemote))
		{
			return false;
		}
		if(!myRemote.equals(branch.myRemote))
		{
			return false;
		}

		return true;
	}

	@Override
	public int hashCode()
	{
		int result = super.hashCode();
		result = 31 * result + myRemote.hashCode();
		result = 31 * result + myNameAtRemote.hashCode();
		return result;
	}

	@Override
	public String toString()
	{
		return super.toString();
	}

	@Nonnull
	@Override
	public String getNameForRemoteOperations()
	{
		return myNameAtRemote;
	}

	@Nonnull
	@Override
	public String getNameForLocalOperations()
	{
		return myName;
	}

}
