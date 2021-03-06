/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package git4idea.actions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.intellij.dvcs.repo.AbstractRepositoryManager;
import com.intellij.dvcs.ui.VcsLogSingleCommitAction;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

public abstract class GitLogSingleCommitAction extends VcsLogSingleCommitAction<GitRepository>
{

	@Nonnull
	@Override
	protected AbstractRepositoryManager<GitRepository> getRepositoryManager(@Nonnull Project project)
	{
		return ServiceManager.getService(project, GitRepositoryManager.class);
	}

	@Override
	@Nullable
	protected GitRepository getRepositoryForRoot(@Nonnull Project project, @Nonnull VirtualFile root)
	{
		return getRepositoryManager(project).getRepositoryForRoot(root);
	}

}
