// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.ui.branch;

import consulo.git.localize.GitBranchesLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import git4idea.GitLocalBranch;
import git4idea.GitReference;
import git4idea.branch.GitBranchOperationType;
import git4idea.branch.GitBranchUtil;
import git4idea.branch.GitNewBranchDialog;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class GitBranchActionsUtil {
    private GitBranchActionsUtil() {
    }

    @RequiredUIAccess
    public static CompletableFuture<?> createOrCheckoutNewBranch(Project project, Collection<GitRepository> repositories, String startPoint) {
        return createOrCheckoutNewBranch(project, repositories, startPoint, GitBranchesLocalize.dialogTitleCreateNewBranch(), null);
    }

    @RequiredUIAccess
    public static CompletableFuture<?> createOrCheckoutNewBranch(Project project,
                                                                 Collection<GitRepository> repositories,
                                                                 String startPoint,
                                                                 LocalizeValue title,
                                                                 @Nullable String initialName) {
        GitNewBranchDialog dialog = new GitNewBranchDialog(
            project,
            repositories,
            title,
            initialName,
            true,
            true,
            false,
            false,
            true,
            GitBranchOperationType.CREATE
        );
        return dialog.showAndGetOptions().thenComposeAsync(options -> {
            if (options == null) {
                return CompletableFuture.completedFuture(null);
            }
            return new GitBranchCheckoutOperation(project, options.repositories()).perform(startPoint, options);
        }, project.getUIAccess());
    }

    /**
     * Calculate initial branch name for the "New branch" actions.
     * <p>
     * Calculation use the following rules:
     * <ul>
     * <li>For selected remote branch - use the corresponding local name</li>
     * <li>For selected local branch - use the same name as the selected branch</li>
     * </ul>
     */
    public static String calculateNewBranchInitialName(String branchName, boolean isRemote) {
        if (branchName.isEmpty()) {
            throw new IllegalArgumentException("Given branch name cannot be empty");
        }

        if (!isRemote) {
            return branchName;
        }

        int slash = branchName.indexOf('/');
        return slash < 0 ? branchName : branchName.substring(slash + 1);
    }

    public static boolean hasTrackingConflicts(Map<GitRepository, GitLocalBranch> conflictingLocalBranches, String remoteBranchName) {
        for (Map.Entry<GitRepository, GitLocalBranch> entry : conflictingLocalBranches.entrySet()) {
            GitBranchTrackInfo trackInfo = GitBranchUtil.getTrackInfoForBranch(entry.getKey(), entry.getValue());
            if (trackInfo != null && !GitReference.BRANCH_NAME_HASHING_STRATEGY.equals(remoteBranchName, trackInfo.remoteBranch().getName())) {
                return true;
            }
        }
        return false;
    }
}
