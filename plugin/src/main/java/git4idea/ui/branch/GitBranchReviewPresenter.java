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

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.application.Application;
import consulo.logging.Logger;
import git4idea.GitStandardLocalBranch;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Supplies a "this branch has an open pull/merge request" review for an arbitrary set of local branches of a
 * repository, not only its current branch. Used to show a review-status icon on branches checked out in a
 * linked worktree.
 */
@ExtensionAPI(ComponentScope.APPLICATION)
public interface GitBranchReviewPresenter {
    /**
     * A single extension's lookup failing (for example an HTTP error from a hosting service) must not stop
     * every other branch's review from ever updating again, so this is the one place that contains it -
     * no extension needs its own error handling.
     */
    static CompletableFuture<Map<GitStandardLocalBranch, @Nullable GitBranchReview>> findReviews(GitRepository repository,
                                                                                                 Set<GitStandardLocalBranch> branches) {
        if (branches.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        CompletableFuture<Map<GitStandardLocalBranch, @Nullable GitBranchReview>> future = Application.get()
            .getExtensionPoint(GitBranchReviewPresenter.class)
            .computeSafeIfAny(it -> it.getReviews(repository, branches));
        if (future == null) {
            return CompletableFuture.completedFuture(noReviews(branches));
        }
        return future.exceptionally(e -> {
            Logger.getInstance(GitBranchReviewPresenter.class).warn("Failed to compute reviews for branches "
                + branches.stream().map(GitStandardLocalBranch::getName).collect(Collectors.joining(", "))
                + " in " + repository.getRoot(), e);
            return noReviews(branches);
        });
    }

    private static Map<GitStandardLocalBranch, @Nullable GitBranchReview> noReviews(Set<GitStandardLocalBranch> branches) {
        Map<GitStandardLocalBranch, @Nullable GitBranchReview> result = new HashMap<>();
        for (GitStandardLocalBranch branch : branches) {
            result.put(branch, null);
        }
        return result;
    }

    /**
     * @return the reviews for {@code branches} in {@code repository}, mapping every one of {@code branches} to its review or
     * to {@code null} once this extension determines that branch has no open review, or null (not a future) when this
     * extension's hosting service does not manage {@code repository} at all, so the caller can try the next extension.
     */
    @Nullable
    CompletableFuture<Map<GitStandardLocalBranch, @Nullable GitBranchReview>> getReviews(GitRepository repository,
                                                                                         Set<GitStandardLocalBranch> branches);
}
