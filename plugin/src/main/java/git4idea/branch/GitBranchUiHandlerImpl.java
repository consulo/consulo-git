/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package git4idea.branch;

import consulo.application.Application;
import consulo.application.progress.ProgressIndicator;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.MessageBoxBuilder;
import consulo.ui.MessageButtonRole;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awtUnsafe.TargetAWT;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.ui.VcsChangesBrowser;
import consulo.versionControlSystem.ui.VcsChangesDialogFactory;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.DialogManager;
import git4idea.GitCommit;
import git4idea.GitUtil;
import git4idea.commands.Git;
import git4idea.merge.GitConflictResolver;
import git4idea.repo.GitRepository;
import git4idea.util.GitSimplePathsBrowser;
import git4idea.util.GitUntrackedFilesHelper;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class GitBranchUiHandlerImpl implements GitBranchUiHandler {
    @Nonnull
    private final Project myProject;
    @Nonnull
    protected final NotificationService myNotificationService;
    @Nonnull
    private final Git myGit;
    @Nonnull
    private final ProgressIndicator myProgressIndicator;

    public GitBranchUiHandlerImpl(@Nonnull Project project, @Nonnull Git git, @Nonnull ProgressIndicator indicator) {
        myProject = project;
        myNotificationService = NotificationService.getInstance();
        myGit = git;
        myProgressIndicator = indicator;
    }

    @Override
    public boolean notifyErrorWithRollbackProposal(
        @Nonnull LocalizeValue title,
        @Nonnull LocalizeValue message,
        @Nonnull LocalizeValue rollbackProposal
    ) {
        StringBuilder description = new StringBuilder();
        if (message.isNotEmpty()) {
            description.append(message.get()).append("<br/>");
        }
        description.append(rollbackProposal.get());
        return askRollback(title, LocalizeValue.of(description.toString()));
    }

    private boolean askRollback(@Nonnull LocalizeValue title, @Nonnull LocalizeValue description) {
        Boolean rollback = DvcsUtil.showAndWait(myProject, () -> MessageBoxBuilder.<Boolean>create()
            .asError()
            .richText()
            .title(title)
            .text(description)
            .button(MessageButtonRole.OK, LocalizeValue.localizeTODO("Rollback"), Boolean.TRUE)
            .asDefaultButton()
            .button(MessageButtonRole.CANCEL, LocalizeValue.localizeTODO("Don't rollback"), Boolean.FALSE)
            .asExitButton()
            .showAsync(myProject));
        return Boolean.TRUE.equals(rollback);
    }

    @Override
    public void showUnmergedFilesNotification(@Nonnull LocalizeValue operationName, @Nonnull Collection<GitRepository> repositories) {
        myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(unmergedFilesErrorTitle(operationName))
            .content(unmergedFilesErrorNotificationDescription(operationName))
            .hyperlinkListener((notification, event) -> {
                if (event.getDescription().equals("resolve")) {
                    GitConflictResolver.Params params = new GitConflictResolver.Params()
                        .setMergeDescription(LocalizeValue.localizeTODO(String.format(
                            "The following files have unresolved conflicts. You need to resolve them before %s.",
                            operationName
                        )))
                        .setErrorNotificationTitle(LocalizeValue.localizeTODO("Unresolved files remain."));
                    new GitConflictResolver(myProject, myGit, GitUtil.getRootsFromRepositories(repositories), params).merge();
                }
            })
            .notify(myProject);
    }

    @Override
    public boolean showUnmergedFilesMessageWithRollback(@Nonnull LocalizeValue operationName, @Nonnull LocalizeValue rollbackProposal) {
        LocalizeValue description = LocalizeValue.localizeTODO(String.format(
            "You have to resolve all merge conflicts before %s.<br/>%s",
            operationName,
            rollbackProposal
        ));
        return askRollback(unmergedFilesErrorTitle(operationName), description);
    }

    @Override
    public void showUntrackedFilesNotification(
        @Nonnull LocalizeValue operationName,
        @Nonnull VirtualFile root,
        @Nonnull Collection<String> relativePaths
    ) {
        GitUntrackedFilesHelper.notifyUntrackedFilesOverwrittenBy(myProject, root, relativePaths, operationName, LocalizeValue.empty());
    }

    @Override
    @RequiredUIAccess
    public boolean showUntrackedFilesDialogWithRollback(
        @Nonnull LocalizeValue operationName,
        @Nonnull LocalizeValue rollbackProposal,
        @Nonnull VirtualFile root,
        @Nonnull Collection<String> relativePaths
    ) {
        return GitUntrackedFilesHelper.showUntrackedFilesDialogWithRollback(
            myProject,
            operationName,
            rollbackProposal,
            root,
            relativePaths
        );
    }

    @Nonnull
    @Override
    public ProgressIndicator getProgressIndicator() {
        return myProgressIndicator;
    }

    @Override
    public int showSmartOperationDialog(
        @Nonnull Project project,
        @Nonnull List<Change> changes,
        @Nonnull Collection<String> paths,
        @Nonnull String operation,
        @Nullable String forceButtonTitle
    ) {
        Disposable browserDisposable = Disposable.newDisposable("Git smart operation files");
        try {
            JComponent fileBrowser;
            if (!changes.isEmpty()) {
                VcsChangesBrowser browser = project.getApplication().getInstance(VcsChangesDialogFactory.class)
                    .changesBrowser(project)
                    .withRollback()
                    .build(browserDisposable);
                browser.setChanges(changes);
                fileBrowser = (JComponent) TargetAWT.to(browser.getComponent());
            }
            else {
                fileBrowser = new GitSimplePathsBrowser(project, paths, browserDisposable);
            }
            return GitSmartOperationDialog.showAndGetAnswer(myProject, fileBrowser, operation, forceButtonTitle);
        }
        finally {
            Disposer.dispose(browserDisposable);
        }
    }

    @Override
    @RequiredUIAccess
    public boolean showBranchIsNotFullyMergedDialog(
        @Nonnull Project project,
        @Nonnull Map<GitRepository, List<GitCommit>> history,
        @Nonnull Map<GitRepository, String> baseBranches,
        @Nonnull String removedBranch
    ) {
        AtomicBoolean restore = new AtomicBoolean();
        Application application = myProject.getApplication();
        application.invokeAndWait(
            () -> restore.set(GitBranchIsNotFullyMergedDialog.showAndGetAnswer(
                myProject,
                history,
                baseBranches,
                removedBranch
            )),
            application.getDefaultModalityState()
        );
        return restore.get();
    }

    @Nonnull
    private static LocalizeValue unmergedFilesErrorTitle(@Nonnull LocalizeValue operationName) {
        return LocalizeValue.localizeTODO("Can't " + operationName + " because of unmerged files");
    }

    @Nonnull
    private static LocalizeValue unmergedFilesErrorNotificationDescription(@Nonnull LocalizeValue operationName) {
        return LocalizeValue.localizeTODO(
            "You have to <a href='resolve'>resolve</a> all merge conflicts before " + operationName + ".<br/>" +
                "After resolving conflicts you also probably would want to commit your files to the current branch."
        );
    }
}
