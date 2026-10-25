// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea;

import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.application.progress.Task;
import consulo.component.ProcessCanceledException;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.InputBoxBuilder;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.localize.DistributedVcsLocalize;
import consulo.versionControlSystem.distributed.push.PushSpec;
import consulo.versionControlSystem.log.Hash;
import git4idea.branch.GitBranchUtil;
import git4idea.push.GitPushOperation;
import git4idea.push.GitPushRepoResult;
import git4idea.push.GitPushSource;
import git4idea.push.GitPushSupport;
import git4idea.push.GitPushTarget;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.validators.GitRefNameValidator;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class GitPushUtil {
    private GitPushUtil() {
    }

    @RequiredUIAccess
    public static CompletableFuture<GitRemoteBranch> findOrPushRemoteBranch(Project project,
                                                                            ProgressIndicator progressIndicator,
                                                                            GitRepository repository,
                                                                            GitRemote remote,
                                                                            GitLocalBranch localBranch,
                                                                            BranchNameInputDialogMessages dialogMessages) {
        if (!(DvcsUtil.getPushSupport(GitVcs.getInstance(project)) instanceof GitPushSupport gitPushSupport)) {
            return CompletableFuture.failedFuture(new ProcessCanceledException());
        }

        GitPushTarget existingPushTarget = findPushTarget(repository, remote, localBranch);
        if (existingPushTarget != null) {
            Hash localHash = repository.getBranches().getHash(localBranch);
            Hash remoteHash = repository.getBranches().getHash(existingPushTarget.getBranch());
            if (Objects.equals(localHash, remoteHash)) {
                return CompletableFuture.completedFuture(existingPushTarget.getBranch());
            }
        }

        CompletableFuture<@Nullable GitPushTarget> pushTargetFuture = existingPushTarget != null
            ? CompletableFuture.completedFuture(existingPushTarget)
            : inputPushTarget(remote, localBranch, dialogMessages);

        return pushTargetFuture.thenCompose(pushTarget -> {
            if (pushTarget == null) {
                return CompletableFuture.failedFuture(new ProcessCanceledException());
            }
            return push(repository, gitPushSupport, localBranch, pushTarget, progressIndicator);
        });
    }

    private static CompletableFuture<GitRemoteBranch> push(GitRepository repository,
                                                           GitPushSupport gitPushSupport,
                                                           GitLocalBranch localBranch,
                                                           GitPushTarget pushTarget,
                                                           ProgressIndicator progressIndicator) {
        CompletableFuture<GitRemoteBranch> future = new CompletableFuture<>();
        ProgressManager.getInstance().runProcessWithProgressAsynchronously(
            new Task.Backgroundable(repository.getProject(), DistributedVcsLocalize.pushProcessPushing(), true) {
                @Override
                public void run(ProgressIndicator indicator) {
                    indicator.setText(DistributedVcsLocalize.pushProcessPushing());
                    PushSpec<GitPushSource, GitPushTarget> pushSpec = new PushSpec<>(GitPushSource.create(localBranch), pushTarget);
                    GitPushRepoResult pushResult = new GitPushOperation(repository.getProject(), gitPushSupport, Map.of(repository, pushSpec), null, false, false)
                        .execute().getResults().get(repository);
                    if (pushResult == null) {
                        throw new IllegalStateException("Missing push result");
                    }
                    if (pushResult.getError() != null) {
                        throw new IllegalStateException(GitLocalize.pushFailedErrorMessage(StringUtil.notNullize(pushResult.getError())).get());
                    }
                }

                @RequiredUIAccess
                @Override
                public void onSuccess() {
                    future.complete(pushTarget.getBranch());
                }

                @RequiredUIAccess
                @Override
                public void onThrowable(Throwable error) {
                    future.completeExceptionally(error);
                }

                @RequiredUIAccess
                @Override
                public void onCancel() {
                    future.completeExceptionally(new ProcessCanceledException());
                }
            },
            progressIndicator
        );
        return future;
    }

    @RequiredUIAccess
    private static CompletableFuture<@Nullable GitPushTarget> inputPushTarget(GitRemote remote,
                                                                             GitLocalBranch localBranch,
                                                                             BranchNameInputDialogMessages dialogMessages) {
        LocalizeValue text = dialogMessages.inputComment().isEmpty()
            ? dialogMessages.inputMessage()
            : LocalizeValue.join(dialogMessages.inputMessage(), LocalizeValue.of("\n"), dialogMessages.inputComment());
        return InputBoxBuilder.text()
            .title(dialogMessages.title())
            .text(text)
            .value(localBranch.getName())
            .showAsync()
            .thenApply(branchName -> {
                if (branchName == null) {
                    return null;
                }
                //always set tracking
                return new GitPushTarget(new GitStandardRemoteBranch(remote, GitRefNameValidator.getInstance().cleanUpBranchName(branchName)), true);
            });
    }

    public static @Nullable GitPushTarget findPushTarget(GitRepository repository, GitRemote remote, GitLocalBranch branch) {
        GitPushTarget fromPushSpec = GitPushTarget.getFromPushSpec(repository, branch);
        if (fromPushSpec != null && remote.equals(fromPushSpec.getBranch().getRemote())) {
            return fromPushSpec;
        }
        GitBranchTrackInfo trackInfo = GitBranchUtil.getTrackInfoForBranch(repository, branch);
        if (trackInfo == null || !trackInfo.getRemote().equals(remote)) {
            return null;
        }
        // use tracking information only for branches with the same local and remote name
        if (!trackInfo.getRemoteBranch().getNameForRemoteOperations().equals(branch.getName())) {
            return null;
        }
        return new GitPushTarget(trackInfo.getRemoteBranch(), false);
    }
}
