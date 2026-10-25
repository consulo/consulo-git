// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.workingTrees.dialog;

import consulo.versionControlSystem.FilePath;
import git4idea.repo.GitRepository;
import git4idea.workingTrees.GitWorktreeAdditionalConfigCopier;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public record GitWorktreeCreationRequest(GitRepository repository,
                                         FilePath workingTreePath,
                                         WorktreeBranchSpec branch,
                                         @Nullable Path setupScriptPath,
                                         boolean copyWorktreeIncludeMatches,
                                         List<GitWorktreeSettingsPanelHandle> additionalSettingsHandles,
                                         Set<GitWorktreeAdditionalConfigCopier> enabledAdditionalConfigCopiers) {
    public GitWorktreeCreationRequest(GitRepository repository, FilePath workingTreePath, WorktreeBranchSpec branch) {
        this(repository, workingTreePath, branch, null, false, List.of(), Set.of());
    }
}
