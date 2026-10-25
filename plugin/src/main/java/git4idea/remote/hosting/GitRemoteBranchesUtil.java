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
package git4idea.remote.hosting;

import consulo.application.progress.ProgressBuilderFactory;
import consulo.application.progress.ProgressManager;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.review.localize.ReviewLocalize;
import consulo.ui.UIAction;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.concurrent.coroutine.Continuation;
import consulo.util.concurrent.coroutine.Coroutine;
import consulo.util.concurrent.coroutine.step.CodeExecution;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.log.VcsLogFilter;
import consulo.versionControlSystem.log.VcsLogFilterCollection;
import consulo.versionControlSystem.log.VcsLogRootFilter;
import consulo.versionControlSystem.log.VcsProjectLog;
import consulo.versionControlSystem.log.VcsLogFilterObject;
import consulo.versionControlSystem.log.Hash;
import git4idea.GitBranch;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;
import git4idea.GitStandardRemoteBranch;
import git4idea.GitUtil;
import git4idea.branch.GitBrancher;
import git4idea.commands.Git;
import git4idea.fetch.GitFetchResult;
import git4idea.fetch.GitFetchSupport;
import git4idea.push.GitSpecialRefRemoteBranch;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepoInfo;
import git4idea.repo.GitRepository;
import git4idea.ui.branch.GitBranchActionsUtil;
import git4idea.ui.branch.GitRemoteBranchCheckoutUtil;
import git4idea.workingTrees.GitCreateWorkingTreeService;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class GitRemoteBranchesUtil {
    private static final String REVIEW_WORKTREES_DIR_NAME = "reviewWorktrees";

    private GitRemoteBranchesUtil() {
    }

    /**
     * Checks if the current HEAD is tracking a branch on remote
     */
    public static boolean isRemoteBranchCheckedOut(GitRepository repository, HostedGitRepositoryRemote remote, String branchName) {
        GitRemote existingRemote = findRemote(repository, remote);
        if (existingRemote == null) {
            return false;
        }
        return isRemoteBranchCheckedOut(repository, new GitStandardRemoteBranch(existingRemote, branchName));
    }

    /**
     * Checks if the current HEAD is tracking a branch on remote
     */
    public static CompletableFuture<Boolean> testRemoteBranchCheckedOut(GitRepository repository,
                                                                        HostedGitRepositoryRemote remote,
                                                                        String branchName) {
        return CompletableFuture.supplyAsync(() -> isRemoteBranchCheckedOut(repository, remote, branchName),
            AppExecutorUtil.getAppExecutorService());
    }

    public static boolean isRemoteBranchCheckedOut(GitRepository repository, GitRemoteBranch branch) {
        if (branch instanceof GitSpecialRefRemoteBranch specialRefBranch) {
            Hash resolved = Git.getInstance().resolveReference(repository, specialRefBranch.getNameForLocalOperations());
            String hash = resolved == null ? null : resolved.asString();
            return Objects.equals(repository.getCurrentRevision(), hash);
        }
        GitLocalBranch localBranch = findLocalBranchTrackingRemote(repository, branch);
        if (localBranch == null) {
            return false;
        }
        return Objects.equals(repository.getCurrentBranchName(), localBranch.getName());
    }

    /**
     * Checks if the current HEAD is either tracking remote {@code branch} or has the same hash
     */
    public static CompletableFuture<Boolean> testRemoteBranchCheckedOut(GitRepository repository, GitRemoteBranch branch) {
        return CompletableFuture.supplyAsync(() -> isRemoteBranchCheckedOut(repository, branch), AppExecutorUtil.getAppExecutorService());
    }

    private static @Nullable GitLocalBranch findLocalBranchTrackingRemote(GitRepository repository, GitRemoteBranch branch) {
        for (GitBranchTrackInfo info : repository.getBranchTrackInfos()) {
            if (info.getRemoteBranch().equals(branch)) {
                return info.getLocalBranch();
            }
        }
        return null;
    }

    public static CompletableFuture<?> fetchAndCheckoutRemoteBranch(GitRepository repository,
                                                                    HostedGitRepositoryRemote remote,
                                                                    String remoteBranch,
                                                                    @Nullable String newLocalBranchPrefix) {
        return withBackgroundProgress(
            repository.getProject(),
            ReviewLocalize.reviewDetailsActionBranchCheckoutRemoteActionDescription(),
            () -> Coroutine.first(CodeExecution.<Object, GitRemoteBranch>apply((input, continuation) -> {
                    GitRemoteBranch branch = findOrCreateRemoteBranch(repository, remote, remoteBranch);
                    if (branch == null || !fetchBranch(repository, branch)) {
                        finishEarly(continuation);
                        return null;
                    }
                    return branch;
                }))
                .then(UIAction.<GitRemoteBranch, Object>apply(branch -> checkoutRemoteBranch(repository, branch, newLocalBranchPrefix, null)))
        );
    }

    public static CompletableFuture<?> fetchAndCheckoutRemoteBranch(GitRepository repository, GitRemoteBranch branch) {
        return fetchAndCheckoutRemoteBranch(repository, branch, null);
    }

    public static CompletableFuture<?> fetchAndCheckoutRemoteBranch(GitRepository repository,
                                                                    GitRemoteBranch branch,
                                                                    @Nullable String newLocalBranchPrefix) {
        return withBackgroundProgress(
            repository.getProject(),
            ReviewLocalize.reviewDetailsActionBranchCheckoutRemoteActionDescription(),
            () -> Coroutine.first(CodeExecution.<Object, Boolean>apply((input, continuation) -> {
                    if (!fetchBranch(repository, branch)) {
                        finishEarly(continuation);
                        return false;
                    }
                    return true;
                }))
                .then(UIAction.<Boolean, Object>apply(fetched -> checkoutRemoteBranch(repository, branch, newLocalBranchPrefix, null)))
        );
    }

    /**
     * @param parentDir            directory the worktree is created under.
     * @param worktreeName         base name for the worktree directory/project.
     * @param newLocalBranchPrefix when a new local branch needs to be created for {@code remoteBranch} (i.e. no local branch
     *                             already tracks it), prefixes its name with this value to disambiguate it from an existing local branch of the
     *                             same name (e.g. a fork PR's head branch).
     * @param onProjectOpened      invoked with the worktree project once it is opened.
     */
    public static CompletableFuture<?> fetchAndCheckoutInNewWorktree(GitRepository repository,
                                                                     HostedGitRepositoryRemote remote,
                                                                     String remoteBranch,
                                                                     Path parentDir,
                                                                     String worktreeName,
                                                                     @Nullable String newLocalBranchPrefix,
                                                                     @Nullable Consumer<Project> onProjectOpened) {
        return withBackgroundProgress(
            repository.getProject(),
            ReviewLocalize.reviewDetailsActionBranchCheckoutRemoteActionDescription(),
            () -> Coroutine.first(CodeExecution.<Object, Object>apply((input, continuation) -> {
                GitRemoteBranch branch = findOrCreateRemoteBranch(repository, remote, remoteBranch);
                if (branch == null) {
                    return null;
                }
                String newLocalBranchName = newLocalBranchPrefix == null ? null : newLocalBranchPrefix + "/" + branch.getNameForRemoteOperations();
                fetchAndCheckoutInNewWorktreeUnderProgress(repository, branch, parentDir, worktreeName, newLocalBranchName, onProjectOpened);
                return null;
            }))
        );
    }

    /**
     * Same as the {@link HostedGitRepositoryRemote} overload, but for an already resolved remote {@code branch}.
     * The {@code branch} can be a {@link GitSpecialRefRemoteBranch}, for example a merge request head ref.
     *
     * @param newLocalBranchName the name of the new local branch when no local branch tracks {@code branch}.
     *                           When it is {@code null}, the new local branch gets the remote branch name.
     *                           Always give it for a {@link GitSpecialRefRemoteBranch}, because a special ref name is not a valid local branch name.
     *                           For example, private or deleted forks are fetched through {@code refs/merge-requests/<iid>/head}.
     *                           Those get a local branch named {@code fork/<author>/<sourceBranch>}.
     */
    public static CompletableFuture<?> fetchAndCheckoutInNewWorktree(GitRepository repository,
                                                                     GitRemoteBranch branch,
                                                                     Path parentDir,
                                                                     String worktreeName,
                                                                     @Nullable String newLocalBranchName,
                                                                     @Nullable Consumer<Project> onProjectOpened) {
        return withBackgroundProgress(
            repository.getProject(),
            ReviewLocalize.reviewDetailsActionBranchCheckoutRemoteActionDescription(),
            () -> Coroutine.first(CodeExecution.<Object, Object>apply((input, continuation) -> {
                fetchAndCheckoutInNewWorktreeUnderProgress(repository, branch, parentDir, worktreeName, newLocalBranchName, onProjectOpened);
                return null;
            }))
        );
    }

    /**
     * The directory for the review worktrees of {@code project}.
     */
    public static Path getReviewWorktreesParentDir(Project project) {
        return GitCreateWorkingTreeService.getSystemTempDir().resolve(REVIEW_WORKTREES_DIR_NAME);
    }

    private static void fetchAndCheckoutInNewWorktreeUnderProgress(GitRepository repository,
                                                                   GitRemoteBranch branch,
                                                                   Path parentDir,
                                                                   String worktreeName,
                                                                   @Nullable String newLocalBranchName,
                                                                   @Nullable Consumer<Project> onProjectOpened) {
        boolean fetchOk = fetchBranch(repository, branch, GitLocalize.progressTextWorktreeFetchingBranch());
        if (!fetchOk) {
            return;
        }

        // Reuse a local branch that already tracks the remote one, or shares the name a regular checkout would have
        // assigned it (tracking may be missing depending on the user's `branch.autoSetupMerge` setting), so the
        // worktree doesn't fail trying to create a branch that already exists.
        // A special ref has no tracking branch, so a local branch from an earlier worktree is found by its name.
        String existingLocalBranchName = branch instanceof GitSpecialRefRemoteBranch ? newLocalBranchName : branch.getNameForRemoteOperations();
        GitLocalBranch existingLocalBranch = findLocalBranchTrackingRemote(repository, branch);
        if (existingLocalBranch == null && existingLocalBranchName != null) {
            GitLocalBranch byName = repository.getBranches().findLocalBranch(existingLocalBranchName);
            if (byName != null && !GitBranchActionsUtil.hasTrackingConflicts(Map.of(repository, byName), branch.getName())) {
                existingLocalBranch = byName;
            }
        }
        GitBranch ref = existingLocalBranch != null ? existingLocalBranch : branch;
        String newBranchName = existingLocalBranch == null ? newLocalBranchName : null;
        try {
            GitCreateWorkingTreeService.getInstance()
                .createOrOpenWorktreeForBranch(repository, ref, parentDir, worktreeName, newBranchName, onProjectOpened);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static CompletableFuture<?> fetchAndShowRemoteBranchInLog(GitRepository repository,
                                                                     GitRemoteBranch branch,
                                                                     @Nullable GitRemoteBranch targetBranch) {
        return withBackgroundProgress(
            repository.getProject(),
            ReviewLocalize.reviewDetailsActionBranchShowRemoteBranchInLogActionDescription(),
            () -> Coroutine.first(CodeExecution.<Object, Boolean>apply((input, continuation) -> {
                    if (!fetchBranch(repository, branch)) {
                        finishEarly(continuation);
                        return false;
                    }
                    return true;
                }))
                .then(UIAction.<Boolean, Object>apply(fetched -> {
                    showRemoteBranchInLog(repository, branch, targetBranch);
                    return null;
                }))
        );
    }

    private static boolean fetchBranch(GitRepository repository, GitRemoteBranch branch) {
        return fetchBranch(repository, branch, LocalizeValue.empty());
    }

    private static boolean fetchBranch(GitRepository repository, GitRemoteBranch branch, LocalizeValue progressText) {
        if (progressText.isNotEmpty()) {
            ProgressManager.progress(progressText);
        }
        String refspec = branch instanceof GitSpecialRefRemoteBranch
            ? branch.getNameForRemoteOperations() + ":" + branch.getNameForLocalOperations()
            : branch.getNameForRemoteOperations();
        GitFetchResult fetchResult = GitFetchSupport.fetchSupport(repository.getProject()).fetch(repository, branch.getRemote(), refspec);
        return fetchResult.showNotificationIfFailed(GitLocalize.notificationTitleFetchFailure());
    }

    public static @Nullable GitRemoteBranch findRemoteBranch(GitRepoInfo repositoryInfo, HostedGitRepositoryRemoteBranch branch) {
        GitRemote headRemote = findRemote(repositoryInfo, branch.remote());
        if (headRemote == null) {
            return null;
        }
        return new GitStandardRemoteBranch(headRemote, branch.branchName());
    }

    public static @Nullable GitRemoteBranch findOrCreateRemoteBranch(GitRepository repository,
                                                                     HostedGitRepositoryRemote remote,
                                                                     String remoteBranch) {
        GitRemote headRemote = findOrCreateRemote(repository, remote);
        if (headRemote == null) {
            notifyRemoteError(repository.getProject(), remote);
            return null;
        }
        return new GitStandardRemoteBranch(headRemote, remoteBranch);
    }

    @RequiredUIAccess
    public static CompletableFuture<?> checkoutRemoteBranch(GitRepository repository,
                                                            GitRemoteBranch branch,
                                                            @Nullable String newLocalBranchPrefix,
                                                            @Nullable Runnable callInAwtLater) {
        // For special refs, there's no backing remote branch.
        // We check out in detached HEAD to avoid confusion from pull/push actions.
        if (branch instanceof GitSpecialRefRemoteBranch) {
            GitBrancher.getInstance(repository.getProject()).checkout(branch.getName(), true, List.of(repository), callInAwtLater);
            return CompletableFuture.completedFuture(null);
        }
        GitLocalBranch existingLocalBranch = findLocalBranchTrackingRemote(repository, branch);
        String suggestedName;
        if (existingLocalBranch != null) {
            suggestedName = existingLocalBranch.getName();
        }
        else if (newLocalBranchPrefix != null) {
            suggestedName = newLocalBranchPrefix + "/" + branch.getNameForRemoteOperations();
        }
        else {
            suggestedName = branch.getNameForRemoteOperations();
        }
        return GitRemoteBranchCheckoutUtil.checkoutRemoteBranch(repository.getProject(), List.of(repository), branch.getName(), suggestedName,
            callInAwtLater);
    }

    @RequiredUIAccess
    public static CompletableFuture<?> checkoutRemoteBranch(Project project, List<GitRepository> repositories, String remoteBranchName) {
        return GitRemoteBranchCheckoutUtil.checkoutRemoteBranch(project, repositories, remoteBranchName);
    }

    @RequiredUIAccess
    private static void showRemoteBranchInLog(GitRepository repository, GitRemoteBranch branch, @Nullable GitRemoteBranch targetBranch) {
        VcsLogFilter branchFilter = targetBranch != null
            ? VcsLogFilterObject.fromRange(targetBranch.getNameForLocalOperations(), branch.getNameForLocalOperations())
            : VcsLogFilterObject.fromBranch(branch.getNameForLocalOperations());
        VcsLogRootFilter repoFilter = VcsLogFilterObject.fromRoots(List.of(repository.getRoot()));
        VcsLogFilterCollection filters = VcsLogFilterObject.collection(branchFilter, repoFilter);
        VcsProjectLog.getInstance(repository.getProject()).openLogTab(filters);
    }

    private static void notifyRemoteError(Project project, HostedGitRepositoryRemote remote) {
        String failedMessage = ReviewLocalize.reviewDetailsActionBranchCheckoutFailedRemote().get();
        String httpUrl = remote.httpUrl();
        String sshUrl = remote.sshUrl();
        if (httpUrl != null) {
            failedMessage += "\n" + httpUrl;
        }
        if (sshUrl != null) {
            failedMessage += "\n" + sshUrl;
        }
        NotificationService.getInstance()
            .newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(ReviewLocalize.reviewDetailsActionBranchCheckoutFailed())
            .content(LocalizeValue.of(failedMessage))
            .notify(project);
    }

    public static @Nullable GitRemote findOrCreateRemote(GitRepository repository, HostedGitRepositoryRemote remote) {
        return findOrCreateRemote(Git.getInstance(), repository, remote);
    }

    public static @Nullable GitRemote findOrCreateRemote(Git git, GitRepository repository, HostedGitRepositoryRemote remote) {
        GitRemote existingRemote = findRemote(repository, remote);
        if (existingRemote != null) {
            return existingRemote;
        }

        String httpUrl = remote.httpUrl();
        String sshUrl = remote.sshUrl();
        boolean preferHttp = shouldAddHttpRemote(repository);
        if (preferHttp && httpUrl != null) {
            return createRemote(git, repository, remote.name(), httpUrl);
        }
        else if (sshUrl != null) {
            return createRemote(git, repository, remote.name(), sshUrl);
        }
        else {
            return null;
        }
    }

    public static @Nullable GitRemote findRemote(GitRepository repository, HostedGitRepositoryRemote remote) {
        return findRemote(repository.getInfo(), remote);
    }

    private static @Nullable GitRemote findRemote(GitRepoInfo repositoryInfo, HostedGitRepositoryRemote remote) {
        String remoteUrl = remote.sshUrl() != null ? remote.sshUrl() : remote.httpUrl();
        if (remoteUrl != null) {
            for (GitRemote gitRemote : repositoryInfo.getRemotes()) {
                String firstUrl = gitRemote.getFirstUrl();
                URI firstUri = firstUrl == null ? null : GitHostingUrlUtil.getUriFromRemoteUrl(firstUrl);
                if (firstUri == null) {
                    continue;
                }
                URI remoteUri = GitHostingUrlUtil.getUriFromRemoteUrl(remoteUrl);
                if (remoteUri == null) {
                    continue;
                }
                if (StringUtil.equalsIgnoreCase(firstUri.getPath(), remoteUri.getPath())
                    && StringUtil.equalsIgnoreCase(firstUri.getHost(), remoteUri.getHost())) {
                    return gitRemote;
                }
            }
        }

        for (GitRemote gitRemote : repositoryInfo.getRemotes()) {
            String url = gitRemote.getFirstUrl();
            if (url != null
                && GitHostingUrlUtil.match(remote.serverUri(), url)
                && StringUtil.trimEnd(StringUtil.trimEnd(url, "/"), GitUtil.DOT_GIT).endsWith(remote.path())) {
                return gitRemote;
            }
        }
        return null;
    }

    private static boolean shouldAddHttpRemote(GitRepository repository) {
        String preferredRemoteUrl = null;
        for (GitRemote remote : repository.getRemotes()) {
            if (remote.getName().equals("origin")) {
                preferredRemoteUrl = remote.getFirstUrl();
                break;
            }
        }
        if (preferredRemoteUrl == null) {
            for (GitRemote remote : repository.getRemotes()) {
                if (remote.getFirstUrl() != null) {
                    preferredRemoteUrl = remote.getFirstUrl();
                    break;
                }
            }
        }
        if (preferredRemoteUrl != null) {
            return preferredRemoteUrl.startsWith("http");
        }
        return true;
    }

    private static @Nullable GitRemote createRemote(Git git, GitRepository repository, String remoteName, String url) {
        String actualName = findNameForRemote(repository, remoteName);
        if (actualName == null) {
            return null;
        }
        git.addRemote(repository, actualName, url);
        repository.update();
        for (GitRemote remote : repository.getRemotes()) {
            if (remote.getName().equals(actualName)) {
                return remote;
            }
        }
        return null;
    }

    /**
     * Returns the {@code preferredName} if it is not taken or adds a numerical index to it
     */
    private static @Nullable String findNameForRemote(GitRepository repository, String preferredName) {
        Set<String> exitingNames = new HashSet<>();
        for (GitRemote remote : repository.getRemotes()) {
            exitingNames.add(remote.getName());
        }
        if (!exitingNames.contains(preferredName)) {
            return preferredName;
        }
        for (int i = 1; i < Integer.MAX_VALUE; i++) {
            String candidate = preferredName + "_" + i;
            if (!exitingNames.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void finishEarly(Continuation<?> continuation) {
        ((Continuation<Object>) continuation).finishEarly(null);
    }

    private static <V> CompletableFuture<V> withBackgroundProgress(Project project, LocalizeValue title, Supplier<Coroutine<?, V>> coroutine) {
        return project.getApplication().getInstance(ProgressBuilderFactory.class)
            .newProgressBuilder(project, title)
            .cancelable()
            .execute(project.getUIAccess(), coroutine);
    }
}
