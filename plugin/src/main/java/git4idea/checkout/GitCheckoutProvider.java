/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package git4idea.checkout;

import consulo.annotation.component.ExtensionImpl;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.disposer.Disposable;
import consulo.git.icon.GitIconGroup;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.image.Image;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.VcsDirtyScopeManager;
import consulo.versionControlSystem.checkout.CheckoutCallback;
import consulo.versionControlSystem.checkout.CheckoutPage;
import consulo.versionControlSystem.checkout.CheckoutProvider;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitVcs;
import git4idea.actions.BasicAction;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandlerListener;
import git4idea.commands.GitShallowCloneOptions;
import git4idea.commands.GitStandardProgressAnalyzer;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checkout provider for the Git
 */
@ExtensionImpl
public class GitCheckoutProvider implements CheckoutProvider {
    private final Git myGit;

    @Inject
    public GitCheckoutProvider(@Nonnull Git git) {
        myGit = git;
    }

    @Override
    public LocalizeValue getName() {
        return GitLocalize.git4ideaVcsName();
    }

    @Override
    public Image getIcon() {
        return GitIconGroup.git();
    }

    @Override
    public LocalizeValue getActionName() {
        return GitLocalize.cloneButton();
    }

    @Override
    @RequiredUIAccess
    public CheckoutPage createPage(@Nonnull Project project, @Nonnull Disposable uiDisposable) {
        return new GitCheckoutPage(project, uiDisposable, myGit);
    }

    public static void clone(
        final Project project,
        @Nonnull final Git git,
        final CheckoutCallback listener,
        final String sourceRepositoryURL,
        final String directoryName,
        final String parentDirectory,
        @Nullable final String puttyKey,
        @Nullable final GitShallowCloneOptions shallowCloneOptions
    ) {
        final AtomicBoolean cloneResult = new AtomicBoolean();
        new Task.Backgroundable(project, GitLocalize.cloningRepository(sourceRepositoryURL)) {
            @Override
            public void run(@Nonnull ProgressIndicator indicator) {
                cloneResult.set(doClone(project, indicator, git, directoryName, parentDirectory, sourceRepositoryURL, puttyKey, shallowCloneOptions));
            }

            @Override
            @RequiredUIAccess
            public void onSuccess() {
                if (!cloneResult.get()) {
                    return;
                }

                listener.directoryCheckedOut(new File(parentDirectory, directoryName), GitVcs.getKey());

                listener.checkoutCompleted();
            }
        }.queue();
    }

    public static boolean doClone(
        @Nonnull Project project,
        @Nonnull ProgressIndicator indicator,
        @Nonnull Git git,
        @Nonnull String directoryName,
        @Nonnull String parentDirectory,
        @Nonnull String sourceRepositoryURL,
        @Nullable String puttyKey,
        @Nullable GitShallowCloneOptions shallowCloneOptions
    ) {
        return cloneNatively(project, indicator, git, new File(parentDirectory), sourceRepositoryURL, directoryName, puttyKey, shallowCloneOptions);
    }

    private static boolean cloneNatively(
        @Nonnull Project project,
        @Nonnull ProgressIndicator indicator,
        @Nonnull Git git,
        @Nonnull File directory,
        @Nonnull String url,
        @Nonnull String cloneDirectoryName,
        @Nullable String puttyKey,
        @Nullable GitShallowCloneOptions shallowCloneOptions
    ) {
        indicator.setIndeterminate(false);
        GitLineHandlerListener progressListener = GitStandardProgressAnalyzer.createListener(indicator);
        GitCommandResult result = git.clone(project, directory, url, puttyKey, cloneDirectoryName, shallowCloneOptions, progressListener);
        if (result.success()) {
            return true;
        }
        NotificationService.getInstance().newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(LocalizeValue.localizeTODO("Clone failed"))
            .content(result.getErrorOutputAsHtmlValue())
            .notify(project);
        return false;
    }
}
