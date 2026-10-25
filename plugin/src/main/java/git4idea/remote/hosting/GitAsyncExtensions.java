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

import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.component.ProcessCanceledException;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.http.client.ServerPath;
import consulo.project.Project;
import consulo.review.util.ComputedResult;
import consulo.util.concurrent.coroutine.MutableObservableValue;
import consulo.util.concurrent.coroutine.ObservableValue;
import consulo.util.lang.Pair;
import consulo.util.lang.function.TripleFunction;
import consulo.versionControlSystem.distributed.repository.VcsRepositoryMappingListener;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;
import git4idea.GitStandardLocalBranch;
import git4idea.branch.GitBranchSyncStatus;
import git4idea.remote.GitRemoteUrlCoordinates;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepoInfo;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryChangeListener;
import git4idea.repo.GitRepositoryFlows;
import git4idea.repo.GitRepositoryManager;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

public final class GitAsyncExtensions {
    private GitAsyncExtensions() {
    }

    public static void changesSignalFlow(GitRepository repository, Disposable parentDisposable, Runnable listener) {
        GitRepositoryFlows.changesSignalFlow(repository, parentDisposable, listener);
    }

    public static ObservableValue<GitRepoInfo> infoStateIn(GitRepository repository, Disposable parentDisposable) {
        return GitRepositoryFlows.infoStateIn(repository, parentDisposable);
    }

    public static void infoFlow(GitRepository repository, Disposable parentDisposable, Consumer<GitRepoInfo> consumer) {
        GitRepositoryFlows.infoFlow(repository, parentDisposable, consumer);
    }

    public static ObservableValue<Set<GitRemoteUrlCoordinates>> gitRemotesStateIn(Project project, Disposable parentDisposable) {
        MutableObservableValue<Set<GitRemoteUrlCoordinates>> state =
            MutableObservableValue.of(collectRemotes(GitRepositoryManager.getInstance(project)));
        gitRemotesFlow(project, parentDisposable, state::set);
        return state;
    }

    public static void gitRemotesFlow(Project project, Disposable parentDisposable, Consumer<Set<GitRemoteUrlCoordinates>> consumer) {
        GitRepositoryManager repoManager = GitRepositoryManager.getInstance(project);
        project.getMessageBus().connect(parentDisposable).subscribe(VcsRepositoryMappingListener.class, () -> {
            consumer.accept(collectRemotes(repoManager));
        });
        project.getMessageBus().connect(parentDisposable).subscribe(GitRepositoryChangeListener.class, repository -> {
            consumer.accept(collectRemotes(repoManager));
        });
        consumer.accept(collectRemotes(repoManager));
    }

    public static Set<GitRemoteUrlCoordinates> collectRemotes(GitRepositoryManager repositoryManager) {
        List<GitRepository> repositories = repositoryManager.getRepositories();
        if (repositories.isEmpty()) {
            return Set.of();
        }

        Set<GitRemoteUrlCoordinates> result = new LinkedHashSet<>();
        for (GitRepository repo : repositories) {
            for (GitRemote remote : repo.getRemotes()) {
                Set<String> urls = new LinkedHashSet<>(remote.getUrls());
                urls.addAll(remote.getPushUrls());
                for (String url : urls) {
                    result.add(new GitRemoteUrlCoordinates(url, remote, repo));
                }
            }
        }
        return result;
    }

    public static @Nullable GitRemoteBranch findFirstRemoteBranchTrackedByCurrent(GitRepoInfo info) {
        GitLocalBranch currentBranch = info.currentBranch();
        return currentBranch == null ? null : findFirstRemoteBranchTrackedBy(info, currentBranch);
    }

    /**
     * Finds the remote branch tracked by {@code branch}, an arbitrary local branch, not necessarily the current one.
     * {@link GitBranchTrackInfo#localBranch()} is a {@link GitLocalBranch}, while a branch checked out in a linked worktree is only
     * known as a {@link GitStandardLocalBranch}. {@code GitReference.equals} compares the class strictly, so the two never
     * compare equal directly - {@code branch} is wrapped as a {@link GitLocalBranch} first.
     */
    public static @Nullable GitRemoteBranch findFirstRemoteBranchTrackedBy(GitRepoInfo info, GitStandardLocalBranch branch) {
        GitLocalBranch localBranch = branch instanceof GitLocalBranch local ? local : new GitLocalBranch(branch.getName());
        for (GitBranchTrackInfo trackInfo : info.branchTrackInfos()) {
            if (trackInfo.localBranch().equals(localBranch)) {
                return trackInfo.remoteBranch();
            }
        }
        return null;
    }

    /**
     * A flow of sync state between a branch and a local repository state.
     * Branch state is represented by a list of commit hashes in {@code commitsState}.
     * {@code commitsState} should hold an ordered list of commits where the last commits in the list is actually the last commits in a branch.
     */
    public static ObservableValue<@Nullable ComputedResult<@Nullable GitBranchSyncStatus>> localCommitsSyncStatus(
        ObservableValue<List<String>> commitsState,
        GitRepository repository,
        Disposable parentDisposable
    ) {
        MutableObservableValue<@Nullable ComputedResult<@Nullable GitBranchSyncStatus>> result =
            MutableObservableValue.of(ComputedResult.loading());
        AtomicReference<@Nullable String> currentRevision = new AtomicReference<>();
        AtomicBoolean revisionKnown = new AtomicBoolean();
        AtomicLong generation = new AtomicLong();

        Runnable update = () -> {
            long current = generation.incrementAndGet();
            String currentRev = currentRevision.get();
            if (currentRev == null) {
                result.set(ComputedResult.success(null));
                return;
            }
            CompletableFuture<GitBranchSyncStatus> syncState;
            try {
                syncState = checkSyncState(repository, currentRev, commitsState.get());
            }
            catch (Exception e) {
                syncState = CompletableFuture.failedFuture(e);
            }
            syncState.whenComplete((status, error) -> {
                if (generation.get() != current) {
                    return;
                }
                result.set(error == null ? ComputedResult.success(status) : toComputedResult(error));
            });
        };

        Disposer.register(parentDisposable, commitsState.addListener(commits -> {
            if (revisionKnown.get()) {
                update.run();
            }
        })::run);
        GitRepositoryFlows.infoFlow(repository, parentDisposable, info -> {
            String revision = info.currentRevision();
            boolean firstValue = !revisionKnown.getAndSet(true);
            if (!Objects.equals(currentRevision.getAndSet(revision), revision) || firstValue) {
                update.run();
            }
        });
        return result;
    }

    private static <T extends @Nullable Object> @Nullable ComputedResult<T> toComputedResult(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof CancellationException || cause instanceof ProcessCanceledException) {
            return null;
        }
        return ComputedResult.failure(cause);
    }

    private static CompletableFuture<GitBranchSyncStatus> checkSyncState(GitRepository repository, String currentRev, List<String> commits) {
        String headSha = commits.get(commits.size() - 1);
        if (currentRev.equals(headSha)) {
            return CompletableFuture.completedFuture(GitBranchSyncStatus.SYNCED);
        }
        if (commits.contains(currentRev)) {
            return CompletableFuture.completedFuture(new GitBranchSyncStatus(true, false));
        }
        return GitCodeReviewUtils.testIsAncestor(repository, headSha, "HEAD").thenApply(isAncestor -> {
            if (isAncestor) {
                return new GitBranchSyncStatus(false, true);
            }
            return new GitBranchSyncStatus(true, true);
        });
    }

    public static <S extends ServerPath, M extends HostedGitRepositoryMapping> ObservableValue<Set<M>> mapToServers(
        ObservableValue<? extends Collection<GitRemoteUrlCoordinates>> remotesState,
        ObservableValue<Set<S>> serversState,
        BiFunction<S, GitRemoteUrlCoordinates, @Nullable M> mapper,
        Disposable parentDisposable
    ) {
        Function<Pair<Collection<GitRemoteUrlCoordinates>, Set<S>>, Set<M>> combiner = pair -> {
            Set<M> result = new LinkedHashSet<>();
            for (GitRemoteUrlCoordinates remote : pair.getFirst()) {
                for (S server : pair.getSecond()) {
                    if (GitHostingUrlUtil.match(server.toURI(), remote.url())) {
                        M mapping = mapper.apply(server, remote);
                        if (mapping != null) {
                            result.add(mapping);
                        }
                        break;
                    }
                }
            }
            return result;
        };

        MutableObservableValue<Set<M>> result =
            MutableObservableValue.of(combiner.apply(Pair.create(remotesState.get(), serversState.get())));
        Runnable update = () -> result.set(combiner.apply(Pair.create(remotesState.get(), serversState.get())));
        Disposer.register(parentDisposable, remotesState.addListener(remotes -> update.run())::run);
        Disposer.register(parentDisposable, serversState.addListener(servers -> update.run())::run);
        return result;
    }

    public static <S extends ServerPath, M extends HostedGitRepositoryMapping> ObservableValue<Set<M>> mapToServers(
        ObservableValue<? extends Collection<GitRemoteUrlCoordinates>> remotesState,
        ObservableValue<Set<S>> serversState,
        ObservableValue<Set<String>> defaultServerAliasesState,
        TripleFunction<Set<S>, Set<String>, GitRemoteUrlCoordinates, @Nullable M> mapper,
        Disposable parentDisposable
    ) {
        MutableObservableValue<Set<M>> result = MutableObservableValue.of(Set.of());
        Runnable update = () -> {
            Set<S> servers = serversState.get();
            Set<String> aliases = defaultServerAliasesState.get();
            Set<M> mappings = new LinkedHashSet<>();
            for (GitRemoteUrlCoordinates remote : remotesState.get()) {
                M mapping = mapper.fun(servers, aliases, remote);
                if (mapping != null) {
                    mappings.add(mapping);
                }
            }
            result.set(mappings);
        };
        update.run();
        Disposer.register(parentDisposable, remotesState.addListener(remotes -> update.run())::run);
        Disposer.register(parentDisposable, serversState.addListener(servers -> update.run())::run);
        Disposer.register(parentDisposable, defaultServerAliasesState.addListener(aliases -> update.run())::run);
        return result;
    }

    public static <S extends ServerPath> void discoverServers(
        ObservableValue<? extends Collection<GitRemoteUrlCoordinates>> remotesState,
        ObservableValue<Set<S>> knownServersState,
        Function<GitRemoteUrlCoordinates, CompletableFuture<@Nullable S>> checkForDedicatedServer,
        Consumer<S> consumer,
        Disposable parentDisposable
    ) {
        discoverServers(remotesState, knownServersState, 10, checkForDedicatedServer, consumer, parentDisposable);
    }

    public static <S extends ServerPath> void discoverServers(
        ObservableValue<? extends Collection<GitRemoteUrlCoordinates>> remotesState,
        ObservableValue<Set<S>> knownServersState,
        int parallelism,
        Function<GitRemoteUrlCoordinates, CompletableFuture<@Nullable S>> checkForDedicatedServer,
        Consumer<S> consumer,
        Disposable parentDisposable
    ) {
        ExecutorService executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("GitAsyncExtensions.discoverServers", 1);
        AtomicLong generation = new AtomicLong();
        AtomicBoolean disposed = new AtomicBoolean();
        Disposer.register(parentDisposable, () -> disposed.set(true));

        Runnable update = () -> {
            List<GitRemoteUrlCoordinates> remotes = new ArrayList<>();
            Set<S> servers = knownServersState.get();
            for (GitRemoteUrlCoordinates remote : remotesState.get()) {
                if (servers.stream().noneMatch(it -> GitHostingUrlUtil.match(it.toURI(), remote.url()))) {
                    remotes.add(remote);
                }
            }
            long current = generation.incrementAndGet();
            executor.execute(() -> {
                if (generation.get() != current || disposed.get()) {
                    return;
                }
                for (int i = 0; i < remotes.size(); i += parallelism) {
                    List<CompletableFuture<?>> chunk = new ArrayList<>();
                    for (GitRemoteUrlCoordinates remote : remotes.subList(i, Math.min(i + parallelism, remotes.size()))) {
                        CompletableFuture<@Nullable S> check;
                        try {
                            check = checkForDedicatedServer.apply(remote);
                        }
                        catch (Exception e) {
                            continue;
                        }
                        chunk.add(check.handle((server, error) -> {
                            if (error == null && server != null && !disposed.get()) {
                                consumer.accept(server);
                            }
                            return null;
                        }));
                    }
                    CompletableFuture.allOf(chunk.toArray(CompletableFuture[]::new)).join();
                    if (disposed.get()) {
                        return;
                    }
                }
            });
        };

        Disposer.register(parentDisposable, remotesState.addListener(remotes -> update.run())::run);
        Disposer.register(parentDisposable, knownServersState.addListener(servers -> update.run())::run);
        update.run();
    }

    public static ObservableValue<@Nullable Boolean> isInCurrentHistory(GitRepository repository,
                                                                         ObservableValue<String> revState,
                                                                         Disposable parentDisposable) {
        return isInCurrentHistory(repository, revState, null, parentDisposable);
    }

    public static ObservableValue<@Nullable Boolean> isInCurrentHistory(GitRepository repository,
                                                                         ObservableValue<String> revState,
                                                                         @Nullable Boolean ifEqualReturn,
                                                                         Disposable parentDisposable) {
        MutableObservableValue<@Nullable Boolean> result = MutableObservableValue.of(null);
        AtomicReference<@Nullable Pair<@Nullable String, String>> lastRevisions = new AtomicReference<>();
        AtomicReference<@Nullable String> currentRevision = new AtomicReference<>();
        AtomicLong generation = new AtomicLong();

        /*
         * Request for the sync state between current local branch and some other branch state.
         */
        Runnable update = () -> {
            String currentRev = currentRevision.get();
            String targetRev = revState.get();
            Pair<@Nullable String, String> revisions = Pair.create(currentRev, targetRev);
            if (Objects.equals(lastRevisions.getAndSet(revisions), revisions)) {
                return;
            }
            long current = generation.incrementAndGet();
            if (currentRev == null) {
                // does not make sense to update on a no-revision head
                result.set(null);
            }
            else if (currentRev.equals(targetRev)) {
                result.set(ifEqualReturn);
            }
            else {
                result.set(null);
                GitCodeReviewUtils.testIsAncestor(repository, targetRev, currentRev).whenComplete((isAncestor, error) -> {
                    if (generation.get() == current) {
                        result.set(error == null && isAncestor != null ? isAncestor : false);
                    }
                });
            }
        };

        Disposer.register(parentDisposable, revState.addListener(rev -> update.run())::run);
        GitRepositoryFlows.infoFlow(repository, parentDisposable, info -> {
            currentRevision.set(info.currentRevision());
            update.run();
        });
        return result;
    }
}
