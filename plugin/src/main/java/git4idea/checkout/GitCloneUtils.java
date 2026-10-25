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
package git4idea.checkout;

import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.checkout.CheckoutCallback;
import consulo.versionControlSystem.distributed.ui.CloneDvcsValidationUtils;
import consulo.versionControlSystem.localize.VcsLocalize;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.commands.Git;
import git4idea.commands.GitShallowCloneOptions;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class GitCloneUtils {
    private static final Logger LOG = Logger.getInstance(GitCloneUtils.class);

    @RequiredUIAccess
    public static void clone(Project project,
                             String selectedUrl,
                             String directoryPath,
                             @Nullable GitShallowCloneOptions shallowCloneOptions,
                             CheckoutCallback checkoutListener) {
        Path parent = Paths.get(directoryPath).toAbsolutePath().getParent();
        LocalizeValue destinationValidation = CloneDvcsValidationUtils.createDestination(parent.toString());
        if (destinationValidation.isNotEmpty()) {
            notifyCreateDirectoryFailed(project, destinationValidation.get());
            return;
        }

        LocalFileSystem lfs = LocalFileSystem.getInstance();
        VirtualFile destinationParent = lfs.findFileByPath(parent.toFile().getAbsolutePath());
        if (destinationParent == null) {
            destinationParent = lfs.refreshAndFindFileByPath(parent.toFile().getAbsolutePath());
        }
        if (destinationParent == null) {
            notifyDestinationNotFound(project);
            return;
        }

        String directoryName = Paths.get(directoryPath).getFileName().toString();
        String parentDirectory = parent.toAbsolutePath().toString();

        GitCheckoutProvider.clone(
            project,
            Git.getInstance(),
            checkoutListener,
            selectedUrl,
            directoryName,
            parentDirectory,
            null,
            shallowCloneOptions
        );
    }

    private static void notifyCreateDirectoryFailed(Project project, String message) {
        LOG.error(VcsLocalize.cloneDialogUnableCreateDestinationError().get() + ": " + message);
        NotificationService.getInstance().newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(VcsLocalize.cloneDialogCloneFailedError())
            .content(GitLocalize.cloneDialogErrorUnableToFindDestinationDirectory())
            .notify(project);
    }

    private static void notifyDestinationNotFound(Project project) {
        LOG.error(GitLocalize.cloneDialogErrorDestinationNotExist().get());
        NotificationService.getInstance().newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(VcsLocalize.cloneDialogCloneFailedError())
            .content(GitLocalize.cloneDialogErrorUnableToFindDestinationDirectory())
            .notify(project);
    }

    private GitCloneUtils() {
    }
}
