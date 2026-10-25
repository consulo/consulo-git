/*
 * Copyright 2013-2026 consulo.io
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
package git4idea.workingTrees.ui;

import consulo.ui.color.ColorValue;
import consulo.versionControlSystem.distributed.ui.VcsRepositoryIconsProvider;
import consulo.versionControlSystem.workspace.VcsWorkspaceGroup;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

import java.util.List;

public record GitWorktreeGroup(GitRepository repository, List<GitWorkingTreesListEntry> entries) implements VcsWorkspaceGroup {
    @Override
    public VirtualFile getRoot() {
        return repository.getRoot();
    }

    @Override
    public @Nullable ColorValue getColor() {
        return VcsRepositoryIconsProvider.getInstance(repository.getProject()).getColor(repository);
    }

    @Override
    public List<GitWorkingTreesListEntry> getWorkspaces() {
        return entries;
    }
}
