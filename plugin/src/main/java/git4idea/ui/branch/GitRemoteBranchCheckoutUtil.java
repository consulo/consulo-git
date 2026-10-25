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
package git4idea.ui.branch;

import consulo.git.localize.GitBranchesLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import git4idea.GitLocalBranch;
import git4idea.GitReference;
import git4idea.GitRemoteBranch;
import git4idea.branch.GitBrancher;
import git4idea.branch.GitNewBranchDialog;
import git4idea.branch.GitNewBranchOptions;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Checks out a remote branch into a new or an existing local branch.
 */
public final class GitRemoteBranchCheckoutUtil {
    private GitRemoteBranchCheckoutUtil() {
    }

    @RequiredUIAccess
    public static CompletableFuture<?> checkoutRemoteBranch(Project project, List<GitRepository> repositories, String remoteBranchName) {
        String suggestedLocalName = null;
        for (GitRepository repository : repositories) {
            GitRemoteBranch remoteBranch = repository.getBranches().findRemoteBranch(remoteBranchName);
            if (remoteBranch != null) {
                suggestedLocalName = remoteBranch.getNameForRemoteOperations();
                break;
            }
        }
        if (suggestedLocalName == null) {
            int slashPosition = remoteBranchName.indexOf('/');
            suggestedLocalName = remoteBranchName.substring(slashPosition + 1);
        }
        return checkoutRemoteBranch(project, repositories, remoteBranchName, suggestedLocalName, null);
    }

    @RequiredUIAccess
    public static CompletableFuture<?> checkoutRemoteBranch(Project project,
                                                            List<GitRepository> repositories,
                                                            String remoteBranchName,
                                                            String suggestedLocalName,
                                                            @Nullable Runnable callInAwtLater) {
        // can have remote conflict if git-svn is used - suggested local name will be equal to selected remote
        if (GitReference.BRANCH_NAME_HASHING_STRATEGY.equals(remoteBranchName, suggestedLocalName)) {
            return askNewBranchNameAndCheckout(project, repositories, remoteBranchName, suggestedLocalName, callInAwtLater);
        }
        Map<GitRepository, GitLocalBranch> conflictingLocalBranches = new LinkedHashMap<>();
        for (GitRepository repository : repositories) {
            GitLocalBranch localBranch = repository.getBranches().findLocalBranch(suggestedLocalName);
            if (localBranch != null) {
                conflictingLocalBranches.put(repository, localBranch);
            }
        }
        if (GitBranchActionsUtil.hasTrackingConflicts(conflictingLocalBranches, remoteBranchName)) {
            return askNewBranchNameAndCheckout(project, repositories, remoteBranchName, suggestedLocalName, callInAwtLater);
        }
        else {
            return new GitBranchCheckoutOperation(project, repositories)
                .perform(remoteBranchName, new GitNewBranchOptions(suggestedLocalName, true, true, false, repositories), callInAwtLater);
        }
    }

    @RequiredUIAccess
    private static CompletableFuture<?> askNewBranchNameAndCheckout(Project project,
                                                                    List<GitRepository> repositories,
                                                                    String remoteBranchName,
                                                                    String suggestedLocalName,
                                                                    @Nullable Runnable callInAwtLater) {
        // Do not allow name conflicts
        return new GitNewBranchDialog(
            project,
            repositories,
            GitBranchesLocalize.branchesCheckoutS(remoteBranchName),
            suggestedLocalName,
            false,
            true
        ).showAndGetOptions().thenAccept(options -> {
            if (options == null) {
                return;
            }
            GitBrancher.getInstance(project).checkoutNewBranchStartingFrom(
                options.name(),
                remoteBranchName,
                options.reset(),
                new ArrayList<>(options.repositories()),
                callInAwtLater
            );
        });
    }
}
