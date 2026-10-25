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

import git4idea.GitReference;
import git4idea.repo.GitRepository;
import git4idea.workingTrees.GitWorktreeAdditionalConfigCopier;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * A snapshot of the New Worktree dialog's fields. Lets the dialog reopen with the same choices after
 * the user closes it to create or edit a .worktreeinclude file.
 */
public record GitWorkingTreeDialogState(GitRepository repository,
                                        @Nullable GitReference existingRef,
                                        boolean createNewBranch,
                                        String newBranchName,
                                        Map<GitWorktreeAdditionalConfigCopier, Boolean> additionalCopiers,
                                        boolean copyWorktreeIncludeMatches,
                                        boolean runSetupScript,
                                        String setupScriptPath,
                                        String projectName,
                                        String parentPath,
                                        boolean configCategoriesExpanded) {
}
