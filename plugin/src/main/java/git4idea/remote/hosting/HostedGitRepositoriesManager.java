// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.remote.hosting;

import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.util.concurrent.coroutine.MutableObservableValue;
import consulo.util.concurrent.coroutine.ObservableValue;
import consulo.util.lang.Pair;
import consulo.versionControlSystem.log.Hash;
import git4idea.GitRemoteBranch;
import git4idea.GitStandardLocalBranch;
import git4idea.repo.GitRepoInfo;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryFlows;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public interface HostedGitRepositoriesManager<M extends HostedGitRepositoryMapping> {
    ObservableValue<Set<M>> getKnownRepositoriesState();

    default Set<M> getKnownRepositories() {
        return getKnownRepositoriesState().get();
    }

    default List<M> findKnownRepositories(GitRepository repository) {
        return getKnownRepositories().stream().filter(it -> it.getRemote().repository() == repository).toList();
    }

    /**
     * Emits the hosted repository mapping and the remote branch tracked by the current local branch (the branch whose
     * pull/merge request the current branch is reviewed against), or {@code null} when the current branch tracks no hosted branch.
     * <p>
     * Re-emits not only when the tracked branch changes, but also when its tip commit moves (e.g. after a push): review
     * features rely on this to re-resolve "which PR/MR corresponds to the current branch" without requiring a branch switch.
     * {@link GitRemoteBranch} equality ignores the commit it points to, so the tip hash is folded into the dedup key explicitly.
     */
    default ObservableValue<@Nullable Pair<M, GitRemoteBranch>> findHostedRemoteBranchTrackedByCurrent(GitRepository repository,
                                                                                                        Disposable parentDisposable) {
        return findHostedRemoteBranchTrackedBy(repository, parentDisposable, GitAsyncExtensions::findFirstRemoteBranchTrackedByCurrent);
    }

    /**
     * Same as {@link #findHostedRemoteBranchTrackedByCurrent}, but for an arbitrary local {@code branch}, not the current branch of
     * {@code repository}. Used for a branch checked out in a linked worktree, since that branch is usually not the
     * repository's current branch.
     */
    default ObservableValue<@Nullable Pair<M, GitRemoteBranch>> findHostedRemoteBranchTrackedBy(GitRepository repository,
                                                                                                 GitStandardLocalBranch branch,
                                                                                                 Disposable parentDisposable) {
        return findHostedRemoteBranchTrackedBy(repository, parentDisposable, info -> GitAsyncExtensions.findFirstRemoteBranchTrackedBy(info, branch));
    }

    private ObservableValue<@Nullable Pair<M, GitRemoteBranch>> findHostedRemoteBranchTrackedBy(
        GitRepository repository,
        Disposable parentDisposable,
        Function<GitRepoInfo, @Nullable GitRemoteBranch> findRemoteBranch
    ) {
        ObservableValue<Set<M>> knownRepositoriesState = getKnownRepositoriesState();
        AtomicReference<@Nullable Pair<@Nullable GitRemoteBranch, @Nullable Hash>> trackedBranch = new AtomicReference<>();
        MutableObservableValue<@Nullable Pair<M, GitRemoteBranch>> result = MutableObservableValue.of(null);

        Runnable update = () -> {
            Pair<@Nullable GitRemoteBranch, @Nullable Hash> tracked = trackedBranch.get();
            GitRemoteBranch branch = tracked == null ? null : tracked.getFirst();
            if (branch == null) {
                result.set(null);
                return;
            }
            M mapping = null;
            for (M it : knownRepositoriesState.get()) {
                if (it.getRemote().repository() == repository && it.getRemote().remote().equals(branch.getRemote())) {
                    mapping = it;
                    break;
                }
            }
            result.set(mapping == null ? null : Pair.create(mapping, branch));
        };

        Disposer.register(parentDisposable, knownRepositoriesState.addListener(repositories -> update.run())::run);
        GitRepositoryFlows.infoFlow(repository, parentDisposable, info -> {
            GitRemoteBranch branch = findRemoteBranch.apply(info);
            Pair<@Nullable GitRemoteBranch, @Nullable Hash> tracked =
                Pair.create(branch, branch == null ? null : info.getRemoteBranchesWithHashes().get(branch));
            if (!Objects.equals(trackedBranch.getAndSet(tracked), tracked)) {
                update.run();
            }
        });
        return result;
    }
}
