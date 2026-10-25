// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.remote.hosting.ui;

import consulo.application.progress.ProgressBuilderFactory;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.review.util.Either;
import consulo.review.util.SingleCoroutineLauncher;
import consulo.ui.UIAction;
import consulo.util.concurrent.coroutine.Continuation;
import consulo.util.concurrent.coroutine.Coroutine;
import consulo.util.concurrent.coroutine.ObservableValue;
import consulo.util.concurrent.coroutine.step.CodeExecution;
import git4idea.GitRemoteBranch;
import git4idea.GitStandardRemoteBranch;
import git4idea.branch.GitBrancher;
import git4idea.fetch.GitFetchSupport;
import git4idea.remote.hosting.GitRemoteBranchesUtil;
import git4idea.remote.hosting.HostedGitRepositoryRemote;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

class ResolveConflictsLocallyViewModelImpl<Error> implements ResolveConflictsLocallyViewModel<Error> {
    private final Project myProject;
    private final GitRepository myGitRepository;
    private final SingleCoroutineLauncher myTaskLauncher = new SingleCoroutineLauncher();
    private final List<GitRepository> myRepositories;
    private final ObservableValue<@Nullable Boolean> myHasConflicts;
    private final ObservableValue<Either<Error, ResolveConflictsLocallyCoordinates>> myRequestOrError;

    ResolveConflictsLocallyViewModelImpl(Disposable parentDisposable,
                                         Project project,
                                         GitRepository gitRepository,
                                         ObservableValue<@Nullable Boolean> hasConflicts,
                                         ObservableValue<Either<Error, ResolveConflictsLocallyCoordinates>> requestOrError) {
        myProject = project;
        myGitRepository = gitRepository;
        myRepositories = List.of(gitRepository);
        myHasConflicts = hasConflicts;
        myRequestOrError = requestOrError;
        Disposer.register(parentDisposable, myTaskLauncher::cancel);
    }

    @Override
    public ObservableValue<@Nullable Boolean> getHasConflicts() {
        return myHasConflicts;
    }

    @Override
    public ObservableValue<Either<Error, ResolveConflictsLocallyCoordinates>> getRequestOrError() {
        return myRequestOrError;
    }

    @Override
    public ObservableValue<Boolean> isBusy() {
        return myTaskLauncher.getBusy();
    }

    @Override
    public void performResolveConflicts(Supplier<CompletableFuture<@Nullable ResolveConflictsMethod>> chooseMethod) {
        myTaskLauncher.launch(() -> chooseMethod.get().<Object>thenCompose(method -> {
            if (method == null) {
                return CompletableFuture.completedFuture(null);
            }
            return switch (method) {
                case REBASE -> rebase();
                case MERGE -> merge();
            };
        }));
    }

    private CompletableFuture<Object> rebase() {
        return prepareAndPerformUpdate((brancher, baseBranch) -> brancher.rebase(myRepositories, baseBranch));
    }

    private CompletableFuture<Object> merge() {
        return prepareAndPerformUpdate(
            (brancher, baseBranch) -> brancher.merge(baseBranch, GitBrancher.DeleteOnMergeOption.NOTHING, myRepositories, false));
    }

    private CompletableFuture<Object> prepareAndPerformUpdate(BiConsumer<GitBrancher, GitRemoteBranch> updater) {
        ResolveConflictsLocallyCoordinates request = myRequestOrError.get().asRightOrNull();
        if (request == null) {
            return CompletableFuture.completedFuture(null);
        }

        return myProject.getApplication().getInstance(ProgressBuilderFactory.class)
            .newProgressBuilder(myProject, GitLocalize.gitFetchProgress())
            .execute(myProject.getUIAccess(), () -> Coroutine.first(CodeExecution.<Object, GitRemoteBranch[]>apply((input, continuation) -> {
                    GitRemote headRemote = getOrCreateRemote(request.headRemoteDescriptor());
                    if (headRemote == null) {
                        finishEarly(continuation);
                        return null;
                    }
                    GitRemoteBranch headBranch = new GitStandardRemoteBranch(headRemote, request.headRefName());

                    GitRemote baseRemote = getOrCreateRemote(request.baseRemoteDescriptor());
                    if (baseRemote == null) {
                        finishEarly(continuation);
                        return null;
                    }
                    GitRemoteBranch baseBranch = new GitStandardRemoteBranch(baseRemote, request.baseRefName());

                    // Fetch base ref
                    GitFetchSupport fetcher = GitFetchSupport.fetchSupport(myProject);
                    fetcher.fetch(myGitRepository, baseRemote, baseBranch.getNameForRemoteOperations());
                    return new GitRemoteBranch[]{headBranch, baseBranch};
                }))
                .then(UIAction.<GitRemoteBranch[], Object>apply(branches -> {
                    // Checkout the head branch
                    GitRemoteBranchesUtil.checkoutRemoteBranch(myGitRepository, branches[0], null, null);

                    // Rebase or merge on the base ref
                    GitBrancher brancher = GitBrancher.getInstance(myProject);

                    updater.accept(brancher, branches[1]);
                    return null;
                })));
    }

    private @Nullable GitRemote getOrCreateRemote(HostedGitRepositoryRemote remoteDescriptor) {
        return GitRemoteBranchesUtil.findOrCreateRemote(myGitRepository, remoteDescriptor);
    }

    @SuppressWarnings("unchecked")
    private static void finishEarly(Continuation<?> continuation) {
        ((Continuation<Object>) continuation).finishEarly(null);
    }
}
