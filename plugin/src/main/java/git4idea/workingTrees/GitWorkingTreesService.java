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
package git4idea.workingTrees;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.Application;
import consulo.application.progress.ProgressBuilderFactory;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.disposer.Disposable;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.Platform;
import consulo.platform.base.localize.CommonLocalize;
import consulo.project.Project;
import consulo.project.ProjectManager;
import consulo.project.ProjectOpenContext;
import consulo.project.ProjectPropertiesComponent;
import consulo.project.RecentProjectsManager;
import consulo.project.ui.notification.NotificationService;
import consulo.project.ui.util.ProjectUIUtil;
import consulo.project.ui.wm.IdeFrame;
import consulo.project.ui.wm.ToolWindowManager;
import consulo.project.ui.wm.ToolWindowManagerListener;
import consulo.project.ui.wm.event.ApplicationActivationListener;
import consulo.project.util.ProjectUtil;
import consulo.ui.MessageBoxBuilder;
import consulo.ui.MessageButtonRole;
import consulo.ui.UIAccess;
import consulo.ui.ex.toolWindow.ToolWindow;
import consulo.util.concurrent.coroutine.Coroutine;
import consulo.util.concurrent.coroutine.step.CodeExecution;
import consulo.util.io.FileUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.VcsToolWindow;
import consulo.versionControlSystem.change.ChangesViewContentManagerListener;
import git4idea.GitRemoteBranch;
import git4idea.GitWorkingTree;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.repo.GitWorkingTreesListener;
import git4idea.workingTrees.dialog.CreateNewBranchWorktreeBranchSpec;
import git4idea.workingTrees.dialog.GitWorktreeCreationRequest;
import git4idea.workingTrees.dialog.WorktreeBranchSpec;
import git4idea.workingTrees.ui.GitWorktreesUiUtil;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitWorkingTreesService implements Disposable {
    private static final String WORKING_TREE_TAB_STATUS_PROPERTY = "Git.Working.Tree.Tab.closed.by.user";
    private static final String WORKING_TREE_TAB_STATUS_OPENED_BY_USER = "opened";
    private static final String WORKING_TREE_TAB_STATUS_CLOSED_BY_USER = "closed";
    static final long WORKING_TREES_REFRESH_INTERVAL = TimeUnit.MINUTES.toMillis(5);

    public static GitWorkingTreesService getInstance(Project project) {
        return project.getInstance(GitWorkingTreesService.class);
    }

    public static boolean isWorktreeCreationSupported(GitRepository repository) {
        GitWorktreeSupportStatus status = getWorktreeSupportStatus(repository.getProject());
        return status instanceof SingleRepositoryGitWorktreeSupportStatus single && single.repository() == repository;
    }

    // The returned value distinguishes unsupported, single-repository, and multi-repository project states.
    public static GitWorktreeSupportStatus getWorktreeSupportStatus(@Nullable Project project) {
        if (project == null || !GitWorkingTreesUtil.isWorkingTreesFeatureEnabled()) {
            return UnsupportedGitWorktreeSupportStatus.INSTANCE;
        }
        List<GitRepository> repositories = findWorktreeCapableRepositories(project);
        return switch (repositories.size()) {
            case 0 -> UnsupportedGitWorktreeSupportStatus.INSTANCE;
            case 1 -> new SingleRepositoryGitWorktreeSupportStatus(repositories.getFirst());
            default -> new MultipleRepositoryGitWorktreeSupportStatus(repositories);
        };
    }

    // All repositories a worktree can be created for. A linked working tree may itself be registered as a VCS
    // root; collapse those into their underlying repository so it is not counted/offered twice.
    public static List<GitRepository> findWorktreeCapableRepositories(Project project) {
        GitRepositoryManager manager = GitRepositoryManager.getInstance(project);
        if (manager == null) {
            return List.of();
        }
        return GitWorkingTreesUtil.mergeLinkedWorktreeRepositories(
            manager.getRepositories(),
            it -> it.getRoot().getPath(),
            it -> it.getRepositoryFiles().getCommonGitDir().getPath(),
            it -> it.getWorkingTreeHolder().getWorkingTrees()
        );
    }

    /**
     * The closest {@code candidates} path that is {@code worktreePath} itself or an ancestor of it (deepest wins), or
     * {@code worktreePath} when none owns it. Used to reopen the whole owning (possibly multi-root) project.
     */
    static Path resolveOwningProjectPath(Path worktreePath, List<Path> candidates) {
        Path result = null;
        for (Path candidate : candidates) {
            if (FileUtil.isAncestor(candidate.toString(), worktreePath.toString(), false)
                && (result == null || candidate.getNameCount() > result.getNameCount())) {
                result = candidate;
            }
        }
        return result != null ? result : worktreePath;
    }

    /**
     * The path to open for {@code tree}: its owning project for the main worktree, or the worktree directory itself
     * for a linked worktree (which must not resolve to a parent project it lives inside).
     */
    static Path resolveProjectPathToOpen(GitWorkingTree tree, List<Path> candidates) {
        Path worktreePath = Path.of(tree.getPath().getPath());
        return tree.isMain() ? resolveOwningProjectPath(worktreePath, candidates) : worktreePath;
    }

    private final Project myProject;
    private final ProgressBuilderFactory myProgressBuilderFactory;

    /**
     * Paths of the working trees whose deletion is in progress, including successfully deleted trees that are still
     * present in the asynchronously refreshed model. {@link GitWorkingTree} itself is unusable as a key: the tab hands out a
     * fresh instance on every reload, while {@link FilePath} is the stable identity of a working tree.
     */
    private final Set<FilePath> myRootsUnderDeletion = ConcurrentHashMap.newKeySet();
    private final Map<FilePath, GitRepository> myRootsAwaitingRemovalFromModel = new ConcurrentHashMap<>();

    private volatile boolean myIdeActive = true;
    private volatile boolean myToolWindowVisible;
    private @Nullable ScheduledFuture<?> myRefreshFuture;

    @Inject
    public GitWorkingTreesService(Project project, ProgressBuilderFactory progressBuilderFactory) {
        myProject = project;
        myProgressBuilderFactory = progressBuilderFactory;

        project.getMessageBus().connect(this).subscribe(GitWorkingTreesListener.class, this::workingTreesLoaded);

        Application application = project.getApplication();
        if (!application.isUnitTestMode() && !application.isHeadlessEnvironment()) {
            scheduleBackgroundRefresh();
        }
    }

    private void scheduleBackgroundRefresh() {
        myProject.getApplication().getMessageBus().connect(this).subscribe(ApplicationActivationListener.class, new ApplicationActivationListener() {
            @Override
            public void applicationActivated(IdeFrame ideFrame) {
                myIdeActive = true;
                updateRefresh();
            }

            @Override
            public void delayedApplicationDeactivated(IdeFrame ideFrame) {
                myIdeActive = false;
                updateRefresh();
            }
        });

        myProject.getMessageBus().connect(this).subscribe(ToolWindowManagerListener.class, new ToolWindowManagerListener() {
            @Override
            public void stateChanged(ToolWindowManager toolWindowManager) {
                updateToolWindowVisible(toolWindowManager);
            }
        });
        myProject.getUIAccess().give(() -> updateToolWindowVisible(ToolWindowManager.getInstance(myProject)));
    }

    private void updateToolWindowVisible(ToolWindowManager toolWindowManager) {
        ToolWindow toolWindow = toolWindowManager.getToolWindow(VcsToolWindow.ID);
        myToolWindowVisible = toolWindow != null && toolWindow.isVisible();
        updateRefresh();
    }

    private synchronized void updateRefresh() {
        boolean shouldRefresh = myIdeActive && myToolWindowVisible;
        ScheduledFuture<?> refreshFuture = myRefreshFuture;
        if (shouldRefresh == (refreshFuture != null)) {
            return;
        }
        if (refreshFuture != null) {
            refreshFuture.cancel(false);
            myRefreshFuture = null;
        }
        if (shouldRefresh) {
            myRefreshFuture = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                this::reloadAllWorkingTrees,
                0,
                WORKING_TREES_REFRESH_INTERVAL,
                TimeUnit.MILLISECONDS
            );
        }
    }

    private void reloadAllWorkingTrees() {
        if (myProject.isDisposed()) {
            return;
        }
        GitWorktreeSupportStatus status = getWorktreeSupportStatus(myProject);
        switch (status) {
            case SingleRepositoryGitWorktreeSupportStatus single -> single.repository().getWorkingTreeHolder().scheduleReload();
            case MultipleRepositoryGitWorktreeSupportStatus multiple ->
                multiple.repositories().forEach(it -> it.getWorkingTreeHolder().scheduleReload());
            default -> {
            }
        }
    }

    @Override
    public synchronized void dispose() {
        ScheduledFuture<?> refreshFuture = myRefreshFuture;
        if (refreshFuture != null) {
            refreshFuture.cancel(false);
            myRefreshFuture = null;
        }
    }

    public boolean shouldWorkingTreesTabBeShown() {
        if (GitWorktreesUiUtil.isEmpty(myProject)) {
            return false;
        }

        String value = ProjectPropertiesComponent.getInstance(myProject).getValue(WORKING_TREE_TAB_STATUS_PROPERTY);
        if (WORKING_TREE_TAB_STATUS_CLOSED_BY_USER.equals(value)) {
            return false;
        }
        if (WORKING_TREE_TAB_STATUS_OPENED_BY_USER.equals(value)) {
            return true;
        }

        return GitWorktreesUiUtil.anyRepositoryHasMultipleWorktrees(myProject);
    }

    public void workingTreesTabOpenedByUser() {
        ProjectPropertiesComponent.getInstance(myProject).setValue(WORKING_TREE_TAB_STATUS_PROPERTY, WORKING_TREE_TAB_STATUS_OPENED_BY_USER);
    }

    public void workingTreesTabClosedByUser() {
        ProjectPropertiesComponent.getInstance(myProject).setValue(WORKING_TREE_TAB_STATUS_PROPERTY, WORKING_TREE_TAB_STATUS_CLOSED_BY_USER);
    }

    /**
     * Runs the {@code git worktree add} command for {@code request}, synchronously, on the calling background thread.
     * The caller runs it under its own background progress (e.g. checking out a PR branch into a new worktree).
     */
    public GitWorkingTreeResult createWorkingTree(GitWorktreeCreationRequest request, boolean force) {
        WorktreeBranchSpec branch = request.branch();
        String newBranchName = switch (branch) {
            case CreateNewBranchWorktreeBranchSpec createNew -> createNew.newBranchName();
            // A remote branch is checked out into a new local branch tracking it.
            default -> branch.getSourceRef() instanceof GitRemoteBranch remoteBranch ? remoteBranch.getNameForRemoteOperations() : null;
        };
        GitCommandResult commandResult =
            Git.getInstance().createWorkingTree(request.repository(), request.workingTreePath(), branch.getSourceRef(), newBranchName, force);
        if (commandResult.success()) {
            return GitWorkingTreeResult.SUCCESS;
        }
        return GitWorkingTreeResult.createFailure(commandResult.getErrorOutputAsHtmlString(), commandResult.getErrorOutput());
    }

    public boolean isCurrentProjectLinkedWorktree() {
        if (!GitWorkingTreesUtil.isWorkingTreesFeatureEnabled()) {
            return false;
        }
        GitRepository repository = singleRepository(myProject);
        return repository != null && repository.getWorkingTreeHolder().getWorkingTrees().stream().anyMatch(it -> it.isCurrent() && !it.isMain());
    }

    public void deleteCurrentProjectWorktree() {
        Project currentProject = myProject;
        GitRepository repository = singleRepository(currentProject);
        if (repository == null) {
            return;
        }
        Collection<GitWorkingTree> worktrees = repository.getWorkingTreeHolder().getWorkingTrees();
        GitWorkingTree currentWorktree = worktrees.stream().filter(it -> it.isCurrent() && !it.isMain()).findFirst().orElse(null);
        if (currentWorktree == null) {
            return;
        }
        String mainWorktreePath = worktrees.stream().filter(GitWorkingTree::isMain).map(it -> it.getPath().getPath()).findFirst().orElse(null);
        if (mainWorktreePath == null) {
            return;
        }

        // Runs on the application scope, not the closing project's scope. The worktree is removed through the service of the
        // still-open main project, or without a project when no open project owns the main worktree.
        closeProject(currentProject).whenCompleteAsync((closed, error) -> {
            Project mainProject = ProjectUtil.findProject(Path.of(mainWorktreePath));
            GitRepository mainRepository = mainProject == null ? null : singleRepository(mainProject);
            if (mainProject != null && mainRepository != null) {
                getInstance(mainProject).deleteWorkingTrees(mainProject, List.of(currentWorktree), mainRepository).join();
            }
            else {
                deleteWorkingTreeWithoutProject(Path.of(mainWorktreePath), currentWorktree);
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    private static @Nullable GitRepository singleRepository(Project project) {
        GitRepositoryManager manager = GitRepositoryManager.getInstance(project);
        if (manager == null) {
            return null;
        }
        List<GitRepository> repositories = manager.getRepositories();
        return repositories.size() == 1 ? repositories.getFirst() : null;
    }

    private static void deleteWorkingTreeWithoutProject(Path mainWorktreePath, GitWorkingTree tree) {
        GitCommandResult commandResult = Git.getInstance().deleteWorkingTreeWithoutProject(mainWorktreePath, tree);

        if (commandResult.success()) {
            removeFromRecentProjects(tree);
            NotificationService.getInstance()
                .newInfo(VcsNotifier.STANDARD_NOTIFICATION)
                .content(GitLocalize.gitWorkingtreesDeleteWorktreeSuccessMessage(tree.getPath().getName()))
                .notify(null);
        }
        else {
            NotificationService.getInstance()
                .newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(GitLocalize.gitWorkingtreesDeleteWorktreesFailureNotificationTitle())
                .content(LocalizeValue.of(commandResult.getErrorOutputAsHtmlString()))
                .notify(null);
        }
    }

    public CompletableFuture<?> openWorkingTreeProject(GitWorkingTree tree) {
        return openWorkingTreeProject(tree, null);
    }

    public CompletableFuture<?> openWorkingTreeProject(GitWorkingTree tree, @Nullable Consumer<Project> onProjectOpened) {
        // Opening a new window is intentionally tied to the application scope so it survives this project closing.
        return CompletableFuture.supplyAsync(() -> Files.exists(Path.of(tree.getPath().getPath())), AppExecutorUtil.getAppExecutorService())
            .thenCompose(exists -> {
                if (!exists) {
                    if (myProject.isDisposed()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    NotificationService.getInstance()
                        .newWarn(VcsNotifier.NOTIFICATION_GROUP_ID)
                        .content(GitLocalize.gitWorkingtreesOpenDirectoryNotFound(tree.getPath().getPresentableUrl()))
                        .notify(myProject);

                    reloadAllWorkingTrees();
                    return CompletableFuture.completedFuture(null);
                }
                return openProjectInNewWindow(resolveProjectPathToOpen(tree)).thenAccept(worktreeProject -> {
                    if (worktreeProject != null && onProjectOpened != null) {
                        onProjectOpened.accept(worktreeProject);
                    }
                });
            });
    }

    /**
     * The path to open for {@code tree}. A <b>linked</b> worktree is a standalone checkout — open its own directory,
     * never a parent project it happens to live inside (e.g. a worktree created under the currently open
     * project, which would otherwise resolve back to that already-open project and do nothing). Only the
     * <b>main</b> worktree resolves to its owning project, so that opening it restores a whole multi-root project.
     */
    private Path resolveProjectPathToOpen(GitWorkingTree tree) {
        List<Path> candidates = new ArrayList<>();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            String basePath = project.getBasePath();
            if (basePath != null) {
                candidates.add(Path.of(basePath));
            }
        }
        for (String recentPath : RecentProjectsManager.getInstance().getRecentPaths()) {
            candidates.add(Path.of(recentPath));
        }
        return resolveProjectPathToOpen(tree, candidates);
    }

    public boolean isWorkingTreeDeletionInProgress(GitWorkingTree workingTree) {
        return myRootsUnderDeletion.contains(workingTree.getPath());
    }

    public CompletableFuture<?> deleteWorkingTree(Project project, GitWorkingTree tree, GitRepository repository) {
        return deleteWorkingTrees(project, List.of(tree), repository);
    }

    /**
     * Deletes {@code trees} one by one, so their git commands, progress indicators and confirmation dialogs don't overlap.
     * Reports the deleted trees with a single notification, so a multi-selection doesn't produce a notification per tree.
     */
    public CompletableFuture<?> deleteWorkingTrees(Project project, List<GitWorkingTree> trees, GitRepository repository) {
        // The whole batch is claimed up front rather than per tree, so a concurrent call can't pick up a tree this one
        // has queued but not started yet. A path already claimed fails to `add` and is dropped here, which also
        // collapses a tree listed twice in {@code trees} into a single deletion.
        // A successfully deleted path remains claimed until WorkingTreesLoaded no longer contains it.
        List<GitWorkingTree> toDelete = new ArrayList<>();
        for (GitWorkingTree tree : trees) {
            if (myRootsUnderDeletion.add(tree.getPath())) {
                toDelete.add(tree);
            }
        }
        if (toDelete.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<GitWorkingTree> deleted = new ArrayList<>();
        LocalizeValue deletionTitle = toDelete.size() == 1
            ? GitLocalize.progressTitleDeletingWorktree()
            : GitLocalize.progressTitleDeletingWorktrees();
        return withBackgroundProgress(project, deletionTitle, () -> {
            for (GitWorkingTree tree : toDelete) {
                if (doDeleteWorkingTree(project, tree, repository)) {
                    deleted.add(tree);
                }
            }
            return null;
        }).whenComplete((result, error) -> {
            for (GitWorkingTree tree : toDelete) {
                if (!myRootsAwaitingRemovalFromModel.containsKey(tree.getPath())) {
                    myRootsUnderDeletion.remove(tree.getPath());
                }
            }
            if (!project.isDisposed()) {
                notifyWorkingTreesDeletedSuccess(project, List.copyOf(deleted));
            }
        });
    }

    /**
     * @return {@code true} if {@code tree} was deleted.
     * <p>
     * {@code tree}'s path must be claimed in {@link #myRootsUnderDeletion} by the caller, which is what keeps {@code git worktree remove}
     * from running twice for one path - see {@link #deleteWorkingTrees}.
     */
    private boolean doDeleteWorkingTree(Project project, GitWorkingTree tree, GitRepository repository) {
        Project existingProject = ProjectUtil.findProject(Path.of(tree.getPath().getPath()));
        if (existingProject != null) {
            if (shouldStopDeletion(project, tree, existingProject)) {
                closeProject(existingProject).join();
            }
            else {
                return false;
            }
        }

        GitCommandResult commandResult = Git.getInstance().deleteWorkingTree(repository, tree);

        if (commandResult.success()) {
            onWorkingTreeDeleted(repository, tree);
            return true;
        }

        if (Platform.current().os().isWindows() && isPermissionDenied(commandResult)) {
            return handleFailedDeletionOnWindows(project, repository, tree);
        }
        notifyWorkingTreeDeletedError(project, commandResult.getErrorOutputAsHtmlString());
        return false;
    }

    private boolean shouldStopDeletion(Project project, GitWorkingTree tree, Project existingProject) {
        return askOnUI(project, () -> {
            MessageBoxBuilder<Boolean> builder = MessageBoxBuilder.<Boolean>create()
                .asQuestion()
                .title(GitLocalize.gitWorkingtreesDialogDeleteWorktreeTitle())
                .text(GitLocalize.gitWorkingtreesDeleteWorktreesWorktreeOpenedCloseOrCancel(tree.getPath().getName(), existingProject.getName()))
                .button(MessageButtonRole.YES, GitLocalize.gitWorkingtreesDeleteWorktreesButtonCloseDelete(), Boolean.TRUE)
                .asDefaultButton()
                .button(MessageButtonRole.NO, GitLocalize.gitWorkingtreesDeleteWorktreesButtonDoNotDelete(), Boolean.FALSE)
                .asExitButton();
            return builder.showAsync();
        });
    }

    private static boolean askOnUI(Project project, Supplier<CompletableFuture<Boolean>> question) {
        Boolean answer = CompletableFuture.supplyAsync(question, project.getUIAccess())
            .thenCompose(future -> future)
            .join();
        return Boolean.TRUE.equals(answer);
    }

    //see consulo.ide.impl.idea.ide.actions.CloseProjectAction
    private static CompletableFuture<?> closeProject(Project project) {
        UIAccess uiAccess = project.getUIAccess();
        return CompletableFuture.supplyAsync(() -> ProjectManager.getInstance().closeAndDisposeAsync(project, uiAccess), uiAccess)
            .thenCompose(future -> future)
            .whenComplete((closed, error) -> RecentProjectsManager.getInstance().updateLastProjectPath());
    }

    private static boolean isPermissionDenied(GitCommandResult result) {
        return result.getErrorOutput().stream().anyMatch(it -> it.toLowerCase().contains("permission denied"));
    }

    private void onWorkingTreeDeleted(GitRepository repository, GitWorkingTree tree) {
        myRootsAwaitingRemovalFromModel.put(tree.getPath(), repository);
        repository.getWorkingTreeHolder().scheduleReload();
        removeFromRecentProjects(tree);
    }

    private static void removeFromRecentProjects(GitWorkingTree tree) {
        RecentProjectsManager recentProjectsManager = RecentProjectsManager.getInstance();
        recentProjectsManager.removePath(tree.getPath().getPath());
        for (String recentPath : recentProjectsManager.getRecentPaths()) {
            if (FileUtil.isAncestor(tree.getPath().getPath(), recentPath, true)) {
                recentProjectsManager.removePath(recentPath);
            }
        }
    }

    private static void notifyWorkingTreesDeletedSuccess(Project project, List<GitWorkingTree> deletedTrees) {
        if (deletedTrees.isEmpty()) {
            return;
        }
        LocalizeValue message = deletedTrees.size() == 1
            ? GitLocalize.gitWorkingtreesDeleteWorktreeSuccessMessage(deletedTrees.getFirst().getPath().getName())
            : GitLocalize.gitWorkingtreesDeleteWorktreesSuccessMessage(deletedTrees.size());
        NotificationService.getInstance()
            .newSuccess(VcsNotifier.NOTIFICATION_GROUP_ID)
            .content(message)
            .notify(project);
    }

    private static void notifyWorkingTreeDeletedError(Project project, String errorOutput) {
        NotificationService.getInstance()
            .newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(GitLocalize.gitWorkingtreesDeleteWorktreesFailureNotificationTitle())
            .content(LocalizeValue.of(errorOutput))
            .notify(project);
    }

    /**
     * Returns {@code true} if {@code tree} was deleted.
     */
    private boolean handleFailedDeletionOnWindows(Project project, GitRepository repository, GitWorkingTree tree) {
        boolean shouldRetry = askOnUI(project, () -> MessageBoxBuilder.<Boolean>create()
            .asQuestion()
            .title(GitLocalize.gitWorkingtreesDialogInuseTitle())
            .text(GitLocalize.gitWorkingtreesDialogInuseMessage(tree.getPath().getPresentableUrl()))
            .button(MessageButtonRole.YES, GitLocalize.gitWorkingtreesDialogInuseButtonTryAgain(), Boolean.TRUE)
            .asDefaultButton()
            .button(MessageButtonRole.CANCEL, CommonLocalize.buttonCancel(), Boolean.FALSE)
            .asExitButton()
            .showAsync());

        if (!shouldRetry) {
            return false;
        }

        if (!FileUtil.delete(Path.of(tree.getPath().getPath()))) {
            notifyWorkingTreeDeletedError(project, "Unknown error while deleting working tree");
            return false;
        }

        onWorkingTreeDeleted(repository, tree);
        return true;
    }

    public CompletableFuture<?> pruneWorkingTrees(Project project, GitRepository repository) {
        return withBackgroundProgress(project, GitLocalize.progressTitlePruningWorktrees(), () -> Git.getInstance().pruneWorktrees(repository))
            .thenAccept(result -> {
                if (!result.success()) {
                    NotificationService.getInstance()
                        .newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                        .title(GitLocalize.gitWorkingtreesPruneWorktreesFailureNotificationTitle())
                        .content(LocalizeValue.of(result.getErrorOutputAsHtmlString()))
                        .notify(project);
                    return;
                }

                repository.getWorkingTreeHolder().scheduleReload();
                NotificationService.getInstance()
                    .newSuccess(VcsNotifier.NOTIFICATION_GROUP_ID)
                    .content(GitLocalize.gitWorkingtreesPruneWorktreeSuccessMessage())
                    .notify(project);
            });
    }

    public CompletableFuture<?> lockWorkingTree(Project project, GitRepository repository, GitWorkingTree tree) {
        return runWorkingTreeLockOperation(
            project, repository,
            GitLocalize.progressTitleLockingWorktree(),
            GitLocalize.gitWorkingtreesLockWorktreeFailureNotificationTitle(),
            GitLocalize.gitWorkingtreesLockWorktreeSuccessMessage(tree.getPath().getName()),
            () -> Git.getInstance().lockWorkingTree(repository, tree)
        );
    }

    public CompletableFuture<?> unlockWorkingTree(Project project, GitRepository repository, GitWorkingTree tree) {
        return runWorkingTreeLockOperation(
            project, repository,
            GitLocalize.progressTitleUnlockingWorktree(),
            GitLocalize.gitWorkingtreesUnlockWorktreeFailureNotificationTitle(),
            GitLocalize.gitWorkingtreesUnlockWorktreeSuccessMessage(tree.getPath().getName()),
            () -> Git.getInstance().unlockWorkingTree(repository, tree)
        );
    }

    private CompletableFuture<?> runWorkingTreeLockOperation(Project project,
                                                             GitRepository repository,
                                                             LocalizeValue progressTitle,
                                                             LocalizeValue failureTitle,
                                                             LocalizeValue successMessage,
                                                             Supplier<GitCommandResult> operation) {
        return withBackgroundProgress(project, progressTitle, operation).thenAccept(result -> {
            if (!result.success()) {
                NotificationService.getInstance()
                    .newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                    .title(failureTitle)
                    .content(LocalizeValue.of(result.getErrorOutputAsHtmlString()))
                    .notify(project);
                return;
            }

            repository.getWorkingTreeHolder().scheduleReload();
            NotificationService.getInstance()
                .newSuccess(VcsNotifier.NOTIFICATION_GROUP_ID)
                .content(successMessage)
                .notify(project);
        });
    }

    public CompletableFuture<@Nullable Project> openProjectInNewWindow(Path path) {
        return GitWorktreeOpenPathProvider.resolveWorktreeOpenPath(myProject, path).thenCompose(pathToOpen -> {
            Project existingProject = null;
            for (Project openProject : ProjectManager.getInstance().getOpenProjects()) {
                String basePath = openProject.getBasePath();
                if (basePath == null) {
                    continue;
                }
                try {
                    if (Files.isSameFile(Path.of(basePath), pathToOpen)) {
                        existingProject = openProject;
                        break;
                    }
                }
                catch (IOException ignored) {
                }
            }

            if (existingProject != null) {
                Project project = existingProject;
                return CompletableFuture.supplyAsync(() -> {
                    ProjectUIUtil.focusProjectWindow(project, false);
                    return project;
                }, project.getUIAccess());
            }

            ProjectOpenContext context = new ProjectOpenContext();
            context.putUserData(ProjectOpenContext.ASK_OPEN_IN_NEW_FRAME, Boolean.TRUE);
            context.putUserData(ProjectOpenContext.ACTIVE_PROJECT, myProject);
            UIAccess uiAccess = myProject.getUIAccess();
            return CompletableFuture.supplyAsync(() -> ProjectManager.getInstance().openProjectAsync(pathToOpen, uiAccess, context), uiAccess)
                .thenCompose(future -> future);
        });
    }

    private <T> CompletableFuture<T> withBackgroundProgress(Project project, LocalizeValue title, Supplier<T> action) {
        return myProgressBuilderFactory.newProgressBuilder(project, title)
            .cancelable()
            .execute(project.getUIAccess(), () -> Coroutine.first(CodeExecution.<Object, T>supply(action)));
    }

    private void workingTreesLoaded(GitRepository repository) {
        Set<FilePath> currentRoots = ConcurrentHashMap.newKeySet();
        for (GitWorkingTree tree : repository.getWorkingTreeHolder().getWorkingTrees()) {
            currentRoots.add(tree.getPath());
        }
        for (Map.Entry<FilePath, GitRepository> entry : myRootsAwaitingRemovalFromModel.entrySet()) {
            FilePath root = entry.getKey();
            if (entry.getValue() == repository && !currentRoots.contains(root) && myRootsAwaitingRemovalFromModel.remove(root, repository)) {
                myRootsUnderDeletion.remove(root);
            }
        }

        if (!myProject.isDisposed()) {
            myProject.getUIAccess().give(() -> {
                if (!myProject.isDisposed()) {
                    myProject.getMessageBus().syncPublisher(ChangesViewContentManagerListener.class).toolWindowMappingChanged();
                }
            });
        }
    }
}
