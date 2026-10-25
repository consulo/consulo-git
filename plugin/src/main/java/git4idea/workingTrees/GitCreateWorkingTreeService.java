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
import consulo.application.ReadAction;
import consulo.application.progress.ProgressBuilderFactory;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.component.util.text.UniqueNameGenerator;
import consulo.container.boot.ContainerPathManager;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.fileEditor.FileEditorManager;
import consulo.fileEditor.event.FileEditorManagerListener;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.module.content.ProjectFileIndex;
import consulo.project.Project;
import consulo.project.ProjectPropertiesComponent;
import consulo.project.RecentProjectsManager;
import consulo.project.util.ProjectUtil;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.concurrent.coroutine.Coroutine;
import consulo.util.concurrent.coroutine.step.CodeExecution;
import consulo.util.io.PathKt;
import consulo.util.lang.ControlFlowException;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.distributed.ui.CloneDvcsValidationUtils;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.GitBranch;
import git4idea.GitReference;
import git4idea.GitWorkingTree;
import git4idea.branch.CheckoutInOtherWorktreeButtonSet;
import git4idea.branch.GitBranchUiHandler.CheckoutInOtherWorktreeDecision;
import git4idea.branch.GitCheckoutInOtherWorktreeDialogs;
import git4idea.commands.GitBranchAlreadyCheckedOutInOtherWorktreeDetector;
import git4idea.commands.GitBranchAlreadyCheckedOutInOtherWorktreeMatch;
import git4idea.repo.GitRepository;
import git4idea.workingTrees.actions.GitChooseWorkingTreeDialog;
import git4idea.workingTrees.dialog.CheckoutExistingWorktreeBranchSpec;
import git4idea.workingTrees.dialog.CreateNewBranchWorktreeBranchSpec;
import git4idea.workingTrees.dialog.GitWorkingTreeDialog;
import git4idea.workingTrees.dialog.GitWorkingTreeDialogExit;
import git4idea.workingTrees.dialog.GitWorktreeCreationRequest;
import git4idea.workingTrees.dialog.GitWorktreeDialogContext;
import git4idea.workingTrees.dialog.GitWorktreeSettingsPanelHandle;
import git4idea.workingTrees.dialog.WorktreeBranchSpec;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Singleton
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
public class GitCreateWorkingTreeService {
    private static final Logger LOG = Logger.getInstance(GitCreateWorkingTreeService.class);

    private static final String LAST_PARENT_PATH_KEY = "Git.CreateWorkingTree.LastParentPath";
    private static final int MAX_WORKTREE_DIR_NAME_LENGTH = 100;

    public static GitCreateWorkingTreeService getInstance() {
        return Application.get().getInstance(GitCreateWorkingTreeService.class);
    }

    // The system temp directory of the IDE
    public static Path getSystemTempDir() {
        return ContainerPathManager.get().getSystemDir().resolve("tmp");
    }

    // The default new-project directory.
    static Path getDefaultParentDir() {
        return ProjectUtil.getProjectsDirectory();
    }

    private final ProgressBuilderFactory myProgressBuilderFactory;

    private final Map<FilePath, GitWorktreePendingCreation> myPendingCreations = new ConcurrentHashMap<>();
    private final List<Runnable> myPendingCreationListeners = new CopyOnWriteArrayList<>();

    @Inject
    public GitCreateWorkingTreeService(ProgressBuilderFactory progressBuilderFactory) {
        myProgressBuilderFactory = progressBuilderFactory;
    }

    /**
     * Without showing the New Worktree dialog, opens {@code branch} in a worktree under {@code parentDir}: creates a new one named
     * {@code worktreeName} (a unique suffix is appended on collision), or opens the existing worktree if {@code branch} is already
     * checked out in one (no-op if that is the current worktree). Blocks until done so it stays under the caller's progress.
     */
    public void createOrOpenWorktreeForBranch(GitRepository repository,
                                              GitBranch branch,
                                              Path parentDir,
                                              String worktreeName,
                                              @Nullable String newBranchName,
                                              @Nullable Consumer<Project> onProjectOpened) throws IOException {
        if (!GitWorkingTreesService.isWorktreeCreationSupported(repository)) {
            return;
        }

        List<GitWorkingTree> checkedOutWorkingTrees =
            GitWorkingTreesUtil.findCheckedOutWorkingTrees(branch, repository.getWorkingTreeHolder().getWorkingTrees(), false);
        boolean force = false;
        if (!checkedOutWorkingTrees.isEmpty()) {
            // If the branch is checked out in the current worktree there's nothing to open; the popup item is hidden in that case.
            if (checkedOutWorkingTrees.stream().anyMatch(GitWorkingTree::isCurrent)) {
                return;
            }
            // Name the single location only when there is one; naming one of several would read as if it were the only one.
            String worktreePathToMention = checkedOutWorkingTrees.size() == 1 ? checkedOutWorkingTrees.getFirst().getPath().getPath() : null;
            if (!confirmCreateNewWorktreeInsteadOfOpening(repository.getProject(), branch, worktreePathToMention)) {
                GitWorkingTree chosen = chooseWorkingTreeToOpen(repository.getProject(), checkedOutWorkingTrees);
                if (chosen != null) {
                    GitWorkingTreesService.getInstance(repository.getProject()).openWorkingTreeProject(chosen, onProjectOpened);
                }
                return;
            }
            force = true;
        }

        String dirName = StringUtil.trimEnd(StringUtil.trimEnd(StringUtil.trimEnd(
            StringUtil.first(PathKt.sanitizeFileName(worktreeName, "_", true, Character::isWhitespace), MAX_WORKTREE_DIR_NAME_LENGTH, false),
            '-'), '_'), '.');
        Files.createDirectories(parentDir);
        Path worktreeDir = parentDir.resolve(UniqueNameGenerator.generateUniqueName(dirName, it -> !Files.exists(parentDir.resolve(it))));
        WorktreeBranchSpec branchSpec = newBranchName != null
            ? new CreateNewBranchWorktreeBranchSpec(branch, newBranchName)
            : new CheckoutExistingWorktreeBranchSpec(branch);
        GitWorktreeCreationRequest request = new GitWorktreeCreationRequest(repository, VcsUtil.getFilePath(worktreeDir.toFile(), true), branchSpec);
        doCreateWorkingTree(request, onProjectOpened, force, false, true).join();
    }

    boolean confirmCreateNewWorktreeInsteadOfOpening(Project project, GitBranch branch, @Nullable String worktreePath) {
        CheckoutInOtherWorktreeDecision decision = onUI(project, () -> GitCheckoutInOtherWorktreeDialogs.buildAndShow(
            project, branch.getName(), worktreePath,
            GitLocalize.workingTreeDialogBranchAlreadyCheckedOutConfirmCreateAnyway(),
            CheckoutInOtherWorktreeButtonSet.PROCEED_OR_OPEN_EXISTING
        ));
        return decision == CheckoutInOtherWorktreeDecision.CHECKOUT_ANYWAY;
    }

    /**
     * {@code workingTrees}' only entry, or the one the user picks from a chooser when there is more than one.
     * Skips the UI-thread hop entirely when {@code workingTrees} already has a single entry.
     */
    private static @Nullable GitWorkingTree chooseWorkingTreeToOpen(Project project, List<GitWorkingTree> workingTrees) {
        if (workingTrees.size() == 1) {
            return workingTrees.getFirst();
        }
        return onUI(project, () -> GitChooseWorkingTreeDialog.chooseAmong(project, workingTrees));
    }

    private static <T> @Nullable T onUI(Project project, @RequiredUIAccess Supplier<CompletableFuture<T>> action) {
        return CompletableFuture.supplyAsync(action, project.getUIAccess()).thenCompose(future -> future).join();
    }

    private static @Nullable String loadLastParentPath(Project project) {
        return ProjectPropertiesComponent.getInstance(project).getValue(LAST_PARENT_PATH_KEY);
    }

    private static void saveLastParentPath(Project project, String path) {
        ProjectPropertiesComponent.getInstance(project).setValue(LAST_PARENT_PATH_KEY, path);
    }

    /**
     * Worktrees whose {@code git worktree add} is still running, keyed by target path; feeds the tab's synthetic "creating" rows.
     */
    public Map<FilePath, GitWorktreePendingCreation> getPendingCreations() {
        return Map.copyOf(myPendingCreations);
    }

    public void addPendingCreationsListener(Runnable listener, Disposable parentDisposable) {
        myPendingCreationListeners.add(listener);
        Disposer.register(parentDisposable, () -> myPendingCreationListeners.remove(listener));
    }

    public boolean isWorkingTreeCreationInProgress(GitWorkingTree workingTree) {
        return myPendingCreations.containsKey(workingTree.getPath());
    }

    public void collectDataAndCreateWorkingTree(GitRepository repository, @Nullable GitReference refFromContext) {
        collectDataAndCreateWorkingTree(repository, refFromContext, List.of(repository), true, null);
    }

    public void collectDataAndCreateWorkingTree(GitRepository repository,
                                                @Nullable GitReference refFromContext,
                                                List<GitRepository> candidateRepositories,
                                                boolean openAfterCreation,
                                                @Nullable Consumer<Project> onProjectOpened) {
        Project project = repository.getProject();
        CompletableFuture.supplyAsync(() -> {
            Path systemTempDir = getSystemTempDir();
            return ReadAction.compute(() -> {
                String lastParentPath = loadLastParentPath(project);
                String initialParentPath = computeInitialParentPath(project, repository, systemTempDir, GitCreateWorkingTreeService::getDefaultParentDir);
                return new GitWorktreeDialogContext(project, repository, refFromContext,
                                                    lastParentPath != null ? lastParentPath : initialParentPath,
                                                    candidateRepositories, openAfterCreation, null);
            });
        }, AppExecutorUtil.getAppExecutorService()).thenAcceptAsync(dialogContext -> showDialog(dialogContext, onProjectOpened), project.getUIAccess());
    }

    @RequiredUIAccess
    private void showDialog(GitWorktreeDialogContext dialogContext, @Nullable Consumer<Project> onProjectOpened) {
        Project project = dialogContext.project();
        GitWorkingTreeDialog dialog = new GitWorkingTreeDialog(dialogContext);
        dialog.showAsync().whenCompleteAsync((exit, error) -> {
            if (exit == GitWorkingTreeDialogExit.OK) {
                GitWorktreeCreationRequest request = dialog.getWorkTreeData();
                FilePath parentPath = request.workingTreePath().getParentPath();
                if (parentPath != null) {
                    saveLastParentPath(project, parentPath.getPath());
                }
                doCreateWorkingTree(request, onProjectOpened, false, true, dialogContext.openAfterCreation());
            }
            else if (exit == GitWorkingTreeDialogExit.CREATE_WORKTREE_INCLUDE) {
                // The dialog already wrote the file and opened it for editing. Keep its field values, so the
                // dialog can reopen with the same choices once that editor tab closes.
                GitWorktreeDialogContext nextContext = dialogContext.withInitialState(dialog.captureState());
                VirtualFile includeFile = dialog.getWorktreeIncludeFile();
                CompletableFuture<?> closed = includeFile == null ? CompletableFuture.completedFuture(null) : waitForEditorClosed(project, includeFile);
                closed.whenCompleteAsync((ignored, closeError) -> {
                    // waitForEditorClosed also resumes when the project disposes first (see its doc comment), so
                    // that case must not be mistaken for "the editor tab closed" and reopen the dialog.
                    if (!project.isDisposed()) {
                        showDialog(nextContext, onProjectOpened);
                    }
                }, project.getUIAccess());
            }
        }, project.getUIAccess());
    }

    /**
     * Completes when {@code file}'s editor tab is closed, or immediately if it isn't open. Teardown
     * runs through a {@link Disposer}-registered {@link Disposable} so it also fires if {@code project} disposes first,
     * instead of leaking the message bus connection.
     */
    CompletableFuture<?> waitForEditorClosed(Project project, VirtualFile file) {
        if (!FileEditorManager.getInstance(project).isFileOpen(file)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?> result = new CompletableFuture<>();
        Disposable disposable = () -> result.complete(null);
        project.getMessageBus().connect(disposable).subscribe(FileEditorManagerListener.class, new FileEditorManagerListener() {
            @Override
            public void fileClosed(FileEditorManager source, VirtualFile closedFile) {
                if (closedFile.equals(file)) {
                    Disposer.dispose(disposable);
                }
            }
        });
        Disposer.register(project, disposable);
        return result;
    }

    /**
     * Searches for a directory that doesn't lie under any of roots of the {@code project}. Falls back to {@code defaultParentDir}
     * when that search fails to leave {@code systemTempDir}, e.g. when {@code project} itself is a scratch worktree opened
     * from a temp directory. {@code defaultParentDir} runs only on that fallback path.
     */
    static String computeInitialParentPath(Project project, GitRepository repository, Path systemTempDir, Supplier<Path> defaultParentDir) {
        VirtualFile baseDir = project.getBaseDir();
        VirtualFile fromProject = baseDir != null ? baseDir.getParent() : null;
        VirtualFile root = fromProject != null ? fromProject : repository.getRoot().getParent();
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        while (root != null && index.isInProjectOrExcluded(root)) {
            root = root.getParent();
        }
        if (root == null || Path.of(root.getPath()).startsWith(systemTempDir)) {
            return defaultParentDir.get().toString();
        }
        return root.getPath();
    }

    CompletableFuture<?> doCreateWorkingTree(GitWorktreeCreationRequest request,
                                             @Nullable Consumer<Project> onProjectOpened,
                                             boolean force,
                                             boolean reportOwnProgress,
                                             boolean openAfterCreation) {
        Project project = request.repository().getProject();
        String path = request.workingTreePath().getPath();
        LocalizeValue destinationValidation = CloneDvcsValidationUtils.createDestination(path);
        if (destinationValidation.isNotEmpty()) {
            GitWorktreeNotifications.notifyCouldNotCreateTargetDir(project, destinationValidation);
            return CompletableFuture.completedFuture(null);
        }

        GitWorkingTreesService gitWTService = GitWorkingTreesService.getInstance(project);
        Path targetWorktreeDir = Path.of(request.workingTreePath().getPath());

        CompletableFuture<Boolean> workingTreeCreated;
        if (reportOwnProgress) {
            workingTreeCreated = myProgressBuilderFactory.newProgressBuilder(project, GitLocalize.progressTitleCreatingWorktree())
                .cancelable()
                .execute(project.getUIAccess(),
                         () -> Coroutine.first(CodeExecution.<Object, Boolean>supply(() -> createAndConfigureWorkingTree(project, request, gitWTService, targetWorktreeDir, force))));
        }
        else {
            workingTreeCreated = CompletableFuture.completedFuture(createAndConfigureWorkingTree(project, request, gitWTService, targetWorktreeDir, force));
        }

        return workingTreeCreated.thenAccept(created -> {
            if (!Boolean.TRUE.equals(created)) {
                return;
            }

            if (!openAfterCreation) {
                registerAsRecentProject(project, targetWorktreeDir);
                request.repository().getWorkingTreeHolder().scheduleReload();
                GitWorktreeNotifications.notifyWorktreeCreated(project, request.branch().getPresentableBranchName(), targetWorktreeDir);
                return;
            }

            openWorktreeProject(project, request, gitWTService, targetWorktreeDir, onProjectOpened);
        });
    }

    /**
     * Runs the git command and, on success, copies the local project configuration into it, all as one progress: the
     * caller passes reportOwnProgress = false when it already runs its own background progress around this call.
     * Returns {@code true} only when the worktree itself was created. A post-creation step failing does not stop the
     * project from opening, since each such step already shows its own error notification.
     */
    private boolean createAndConfigureWorkingTree(Project project,
                                                  GitWorktreeCreationRequest request,
                                                  GitWorkingTreesService gitWTService,
                                                  Path targetWorktreeDir,
                                                  boolean force) {
        if (!createGitWorkingTree(project, request, gitWTService, force)) {
            return false;
        }

        copyProjectConfigStep(project, request, targetWorktreeDir);

        GitWorktreeConfigCopyContext configCopyContext = new GitWorktreeConfigCopyContext(project, request.repository().getRoot(), targetWorktreeDir);
        runAdditionalConfigCopiers(project, request, configCopyContext, targetWorktreeDir);
        applyAdditionalSettingsHandles(project, request, configCopyContext, targetWorktreeDir);

        runSetupScriptStep(project, request, targetWorktreeDir);
        refreshCopiedConfigDirectories(targetWorktreeDir);
        return true;
    }

    /**
     * Backstops {@link #runAdditionalConfigCopiers} and {@link #applyAdditionalSettingsHandles}: refreshes the project store and {@code .run} in
     * case either wrote there through a raw NIO call, same as {@link GitWorktreeProjectConfigService} already does
     * for its own copied files. Without a refresh, the native file watcher has to work through the whole
     * freshly checked-out worktree first, and the new project can open and show stale or empty settings for
     * minutes.
     */
    private static void refreshCopiedConfigDirectories(Path targetWorktreeDir) {
        LocalFileSystem localFileSystem = LocalFileSystem.getInstance();
        for (String relativeDir : List.of(Project.DIRECTORY_STORE_FOLDER, ".run")) {
            VirtualFile virtualFile = localFileSystem.refreshAndFindFileByNioFile(targetWorktreeDir.resolve(relativeDir));
            if (virtualFile == null) {
                continue;
            }
            VirtualFileUtil.markDirtyAndRefresh(false, true, true, virtualFile);
        }
    }

    /**
     * Creates the worktree at the git level, retrying once if it's already checked out elsewhere and the user
     * confirms doing it anyway. Notifies and returns false on failure.
     */
    private boolean createGitWorkingTree(Project project, GitWorktreeCreationRequest request, GitWorkingTreesService gitWTService, boolean force) {
        GitWorkingTreeResult result = createWorkingTreeTracked(gitWTService, request, force);
        if (result.isSuccess()) {
            return true;
        }

        GitBranchAlreadyCheckedOutInOtherWorktreeMatch otherWorktreeMatch =
            GitBranchAlreadyCheckedOutInOtherWorktreeDetector.matchInOutput(result.getErrorOutput());
        if (otherWorktreeMatch != null &&
            confirmCreateWorktreeIgnoringOtherWorktree(project, otherWorktreeMatch.branchName(), otherWorktreeMatch.worktreePath())) {
            result = createWorkingTreeTracked(gitWTService, request, true);
        }
        if (result.isSuccess()) {
            return true;
        }

        GitWorktreeNotifications.notifyWorktreeAddFailed(project, result.getErrorOutputAsHtmlString());
        return false;
    }

    /**
     * Copies every .worktreeinclude match into the new worktree. Never throws; notifies on any failure.
     */
    private static void copyProjectConfigStep(Project project, GitWorktreeCreationRequest request, Path targetWorktreeDir) {
        if (!request.copyWorktreeIncludeMatches()) {
            return;
        }

        try {
            List<Path> failedFiles = GitWorktreeProjectConfigService.getInstance(project)
                .copyAndCleanUpWorktreeIncludeFiles(request.repository().getRoot(), targetWorktreeDir);

            if (!failedFiles.isEmpty()) {
                GitWorktreeNotifications.notifyConfigCopyFailed(project, failedFiles);
            }
        }
        catch (Throwable e) {
            LOG.error("Failed to copy the local project configuration into the new worktree", e);
            GitWorktreeNotifications.notifyConfigCopyFailedUnexpectedly(project, targetWorktreeDir);
        }
    }

    /**
     * Runs every enabled {@link GitWorktreeAdditionalConfigCopier} extension; notifies once if any of them failed.
     */
    private static void runAdditionalConfigCopiers(Project project,
                                                   GitWorktreeCreationRequest request,
                                                   GitWorktreeConfigCopyContext configCopyContext,
                                                   Path targetWorktreeDir) {
        if (!GitWorktreeAdditionalConfigCopier.copyAll(configCopyContext, request.enabledAdditionalConfigCopiers())) {
            GitWorktreeNotifications.notifyAdditionalConfigCopyFailed(project, targetWorktreeDir);
        }
    }

    /**
     * Runs every additional settings panel's post-creation action. Notifies (and logs) per handle that throws,
     * and keeps running the rest.
     */
    private static void applyAdditionalSettingsHandles(Project project,
                                                       GitWorktreeCreationRequest request,
                                                       GitWorktreeConfigCopyContext configCopyContext,
                                                       Path targetWorktreeDir) {
        for (GitWorktreeSettingsPanelHandle handle : request.additionalSettingsHandles()) {
            try {
                handle.apply(configCopyContext).join();
            }
            catch (Throwable e) {
                LOG.error("Failed to apply additional worktree settings", e);
                GitWorktreeNotifications.notifyAdditionalSettingsFailed(project, targetWorktreeDir);
            }
        }
    }

    /**
     * Runs the request's setup script, if any. Notifies on failure.
     */
    private static void runSetupScriptStep(Project project, GitWorktreeCreationRequest request, Path targetWorktreeDir) {
        Path scriptPath = request.setupScriptPath();
        if (scriptPath == null) {
            return;
        }
        boolean succeeded = GitWorktreeSetupScriptRunner.runSetupScript(project, scriptPath, targetWorktreeDir);
        if (!succeeded) {
            GitWorktreeNotifications.notifySetupScriptFailed(project, scriptPath);
        }
    }

    /**
     * Opens the new worktree's project detached from {@code project}: opening in the same window closes and
     * disposes the current project, so this must survive that disposal. A failure only logs once {@code project}
     * is already disposed, since a notification on it would not show.
     */
    private static void openWorktreeProject(Project project,
                                            GitWorktreeCreationRequest request,
                                            GitWorkingTreesService gitWTService,
                                            Path targetWorktreeDir,
                                            @Nullable Consumer<Project> onProjectOpened) {
        gitWTService.openProjectInNewWindow(targetWorktreeDir).whenComplete((worktreeProject, error) -> {
            if (error != null && ControlFlowException.isControlFlow(error)) {
                request.repository().getWorkingTreeHolder().scheduleReload();
                return;
            }
            if (error != null) {
                LOG.error("Failed to open the new worktree project", error);
                if (!project.isDisposed()) {
                    GitWorktreeNotifications.notifyOpenProjectFailed(project, targetWorktreeDir);
                    request.repository().getWorkingTreeHolder().scheduleReload();
                }
                return;
            }

            if (worktreeProject != null) {
                if (onProjectOpened != null) {
                    onProjectOpened.accept(worktreeProject);
                }
            }
            else {
                request.repository().getWorkingTreeHolder().scheduleReload();
            }
        });
    }

    private static void registerAsRecentProject(Project project, Path worktreePath) {
        GitWorktreeOpenPathProvider.resolveWorktreeOpenPath(project, worktreePath)
            .thenAccept(pathToOpen -> RecentProjectsManager.getInstance().addRecentPath(pathToOpen.toString()));
    }

    private GitWorkingTreeResult createWorkingTreeTracked(GitWorkingTreesService gitWTService, GitWorktreeCreationRequest request, boolean force) {
        myPendingCreations.put(request.workingTreePath(), GitWorktreePendingCreation.from(request));
        firePendingCreationsChanged();
        try {
            return gitWTService.createWorkingTree(request, force);
        }
        finally {
            myPendingCreations.remove(request.workingTreePath());
            firePendingCreationsChanged();
        }
    }

    private void firePendingCreationsChanged() {
        myPendingCreationListeners.forEach(Runnable::run);
    }

    boolean confirmCreateWorktreeIgnoringOtherWorktree(Project project, String branchName, @Nullable String worktreePath) {
        CheckoutInOtherWorktreeDecision decision = onUI(project, () -> GitCheckoutInOtherWorktreeDialogs.buildAndShow(
            project, branchName, worktreePath,
            GitLocalize.workingTreeDialogBranchAlreadyCheckedOutConfirmCreateAnyway(),
            CheckoutInOtherWorktreeButtonSet.PROCEED_OR_CANCEL
        ));
        return decision == CheckoutInOtherWorktreeDecision.CHECKOUT_ANYWAY;
    }
}
