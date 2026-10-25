// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
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

import consulo.application.progress.ProgressBuilderFactory;
import consulo.git.localize.GitBranchesLocalize;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.platform.base.localize.CommonLocalize;
import consulo.project.Project;
import consulo.ui.MessageBoxBuilder;
import consulo.ui.MessageButtonRole;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.concurrent.coroutine.Coroutine;
import consulo.util.concurrent.coroutine.step.CodeExecution;
import consulo.versionControlSystem.VcsException;
import git4idea.GitLocalBranch;
import git4idea.branch.GitBrancher;
import git4idea.branch.GitNewBranchOptions;
import git4idea.history.GitHistoryUtils;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class GitBranchCheckoutOperation {
    private static final Logger LOG = Logger.getInstance(GitBranchCheckoutOperation.class);

    private final Project myProject;
    private final Collection<GitRepository> myRepositories;
    private final GitBrancher myBrancher;

    public GitBranchCheckoutOperation(Project project, Collection<GitRepository> repositories) {
        myProject = project;
        myRepositories = repositories;
        myBrancher = GitBrancher.getInstance(project);
        assert !repositories.isEmpty();
    }

    @RequiredUIAccess
    public CompletableFuture<?> perform(String startPoint, GitNewBranchOptions options) {
        return perform(startPoint, options, null);
    }

    @RequiredUIAccess
    public CompletableFuture<?> perform(String startPoint, GitNewBranchOptions options, @Nullable Runnable callInAwtLater) {
        boolean checkout = options.checkout();
        String name = options.name();
        boolean reset = options.reset();

        UIAccess uiAccess = myProject.getUIAccess();
        return checkLocalHasMoreCommits(myProject, myRepositories, name, startPoint).thenComposeAsync(localHasMoreCommits -> {
            CompletableFuture<?> result = checkout
                ? performCheckout(startPoint, name, localHasMoreCommits, reset, callInAwtLater)
                : performCreate(startPoint, name, localHasMoreCommits, reset, callInAwtLater);
            return result.thenApply(ignored -> null);
        }, uiAccess);
    }

    @RequiredUIAccess
    private CompletableFuture<?> performCheckout(String startPoint,
                                                 String name,
                                                 boolean localHasMoreCommits,
                                                 boolean reset,
                                                 @Nullable Runnable callInAwtLater) {
        if (localHasMoreCommits) {
            MessageBoxBuilder<@Nullable CheckoutConflictResolution> builder = MessageBoxBuilder.create();
            builder.asQuestion().title(GitBranchesLocalize.checkout0(startPoint));
            if (reset) {
                builder.text(GitBranchesLocalize.branchesCheckoutWithResetLocalHasMoreCommits(name, startPoint));
                builder.button(MessageButtonRole.YES, GitBranchesLocalize.checkoutAndRebase(), CheckoutConflictResolution.REBASE).asDefaultButton();
                builder.button(MessageButtonRole.NO, GitBranchesLocalize.branchesDropLocalCommits(), CheckoutConflictResolution.RESET);
            }
            else {
                builder.text(GitBranchesLocalize.branchesCheckoutLocalHasMoreCommits(name, startPoint));
                builder.button(MessageButtonRole.YES, GitBranchesLocalize.checkoutAndRebase(), CheckoutConflictResolution.REBASE).asDefaultButton();
                builder.button(MessageButtonRole.NO, GitBranchesLocalize.branchesCheckoutLocal(), CheckoutConflictResolution.USE_LOCAL);
            }
            builder.button(MessageButtonRole.CANCEL, CommonLocalize.buttonCancel(), (CheckoutConflictResolution) null).asExitButton();
            return builder.showAsync().thenAcceptAsync(result -> {
                if (result != null) {
                    checkout(startPoint, name, result, callInAwtLater);
                }
            }, myProject.getUIAccess());
        }
        else {
            checkout(startPoint, name, reset ? CheckoutConflictResolution.RESET : CheckoutConflictResolution.TRY, callInAwtLater);
            return CompletableFuture.completedFuture(null);
        }
    }

    @RequiredUIAccess
    private CompletableFuture<?> performCreate(String startPoint,
                                               String name,
                                               boolean localHasMoreCommits,
                                               boolean reset,
                                               @Nullable Runnable callInAwtLater) {
        if (localHasMoreCommits) {
            MessageBoxBuilder<Boolean> builder = MessageBoxBuilder.create();
            builder.asQuestion().title(GitBranchesLocalize.checkout0(startPoint));
            LocalizeValue yesText;
            if (reset) {
                builder.text(GitBranchesLocalize.branchesCreateWithResetLocalHasMoreCommits(name, startPoint));
                yesText = GitBranchesLocalize.branchesDropLocalCommits();
            }
            else {
                builder.text(GitBranchesLocalize.branchesCreateLocalHasMoreCommits(name, startPoint));
                yesText = GitBranchesLocalize.newBranchDialogOperationCreateName();
            }
            builder.button(MessageButtonRole.YES, yesText, Boolean.TRUE).asDefaultButton();
            builder.button(MessageButtonRole.CANCEL, CommonLocalize.buttonCancel(), Boolean.FALSE).asExitButton();
            return builder.showAsync().thenAcceptAsync(result -> {
                if (Boolean.TRUE.equals(result)) {
                    create(startPoint, name, reset, callInAwtLater);
                }
            }, myProject.getUIAccess());
        }
        else {
            create(startPoint, name, reset, callInAwtLater);
            return CompletableFuture.completedFuture(null);
        }
    }

    private void checkout(String startPoint,
                          String name,
                          CheckoutConflictResolution localConflictResolution,
                          @Nullable Runnable callInAwtLater) {
        List<GitRepository> reposWithLocalBranch = new ArrayList<>();
        List<GitRepository> reposWithoutLocalBranch = new ArrayList<>();
        for (GitRepository repository : myRepositories) {
            (repository.getBranches().findLocalBranch(name) != null ? reposWithLocalBranch : reposWithoutLocalBranch).add(repository);
        }

        //checkout existing branch
        if (!reposWithLocalBranch.isEmpty()) {
            switch (localConflictResolution) {
                case USE_LOCAL -> myBrancher.checkout(name, false, reposWithLocalBranch, callInAwtLater);
                case RESET -> myBrancher.checkoutNewBranchStartingFrom(name, startPoint, true, reposWithLocalBranch, callInAwtLater);
                case REBASE -> myBrancher.rebase(reposWithLocalBranch, startPoint, name);
                case TRY -> myBrancher.checkoutNewBranchStartingFrom(name, startPoint, false, reposWithLocalBranch, callInAwtLater);
            }
        }

        //checkout new
        if (!reposWithoutLocalBranch.isEmpty()) {
            myBrancher.checkoutNewBranchStartingFrom(name, startPoint, false, reposWithoutLocalBranch, callInAwtLater);
        }
    }

    private void create(String startPoint, String name, boolean reset, @Nullable Runnable callInAwtLater) {
        List<GitRepository> reposWithLocalBranch = new ArrayList<>();
        List<GitRepository> reposWithoutLocalBranch = new ArrayList<>();
        for (GitRepository repository : myRepositories) {
            (repository.getBranches().findLocalBranch(name) != null ? reposWithLocalBranch : reposWithoutLocalBranch).add(repository);
        }

        if (!reposWithLocalBranch.isEmpty() && reset) {
            List<GitRepository> currentBranchOfSameName = new ArrayList<>();
            List<GitRepository> currentBranchOfDifferentName = new ArrayList<>();
            for (GitRepository repository : reposWithLocalBranch) {
                (name.equals(repository.getCurrentBranchName()) ? currentBranchOfSameName : currentBranchOfDifferentName).add(repository);
            }
            //git checkout -B for current branches with the same name (cannot force update current branch) and git branch -f for not current
            if (!currentBranchOfSameName.isEmpty()) {
                myBrancher.checkoutNewBranchStartingFrom(name, startPoint, true, currentBranchOfSameName, callInAwtLater);
            }
            if (!currentBranchOfDifferentName.isEmpty()) {
                myBrancher.createBranch(name, associateWith(currentBranchOfDifferentName, startPoint), true, callInAwtLater);
            }
        }

        if (!reposWithoutLocalBranch.isEmpty()) {
            myBrancher.createBranch(name, associateWith(reposWithoutLocalBranch, startPoint), callInAwtLater);
        }
    }

    private static Map<GitRepository, String> associateWith(List<GitRepository> repositories, String startPoint) {
        Map<GitRepository, String> result = new LinkedHashMap<>();
        for (GitRepository repository : repositories) {
            result.put(repository, startPoint);
        }
        return result;
    }

    static CompletableFuture<Boolean> checkLocalHasMoreCommits(Project project,
                                                               Collection<GitRepository> repositories,
                                                               String localBranch,
                                                               String startPoint) {
        List<GitRepository> existingLocalBranches = new ArrayList<>();
        for (GitRepository repository : repositories) {
            GitLocalBranch local = repository.getBranches().findLocalBranch(localBranch);
            if (local != null) {
                existingLocalBranches.add(repository);
            }
        }

        if (existingLocalBranches.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return checkCommitsUnderProgress(project, existingLocalBranches, startPoint, localBranch);
    }

    private static CompletableFuture<Boolean> checkCommitsUnderProgress(Project project,
                                                                        List<GitRepository> repositories,
                                                                        String startRef,
                                                                        String branchName) {
        return project.getApplication().getInstance(ProgressBuilderFactory.class)
            .newProgressBuilder(project, GitBranchesLocalize.branchesCheckingExistingCommitsProcess())
            .modal()
            .execute(
                project.getUIAccess(),
                () -> Coroutine.first(CodeExecution.<Object, Boolean>apply(
                    (input, continuation) -> checkCommitsBetweenRefAndBranchName(project, repositories, startRef, branchName)
                ))
            );
    }

    private static boolean checkCommitsBetweenRefAndBranchName(Project project,
                                                               List<GitRepository> repositories,
                                                               String startRef,
                                                               String branchName) {
        for (GitRepository repository : repositories) {
            GitLocalBranch existingBranch = repository.getBranches().findLocalBranch(branchName);
            if (existingBranch != null && hasCommits(project, repository, startRef, existingBranch.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCommits(Project project, GitRepository repository, String startRef, String endRef) {
        try {
            return !GitHistoryUtils.collectTimedCommits(project, repository.getRoot(), startRef + ".." + endRef).isEmpty();
        }
        catch (VcsException ex) {
            LOG.warn("Couldn't collect commits in " + repository.getPresentableUrl() + " for " + startRef + ".." + endRef);
            return true;
        }
    }
}
