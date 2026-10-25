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

import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.versionControlSystem.VcsNotifier;
import consulo.virtualFileSystem.VirtualFile;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Every user-facing notification {@link GitCreateWorkingTreeService} shows while creating a worktree.
 */
public final class GitWorktreeNotifications {
    public static void notifyCouldNotCreateTargetDir(Project project, LocalizeValue message) {
        notifyError(project, GitLocalize.notificationTitleWorktreeCreationFailed(), message);
    }

    public static void notifyWorktreeAddFailed(Project project, String errorHtml) {
        notifyError(project, GitLocalize.notificationTitleWorktreeCreationFailed(), LocalizeValue.of(errorHtml));
    }

    public static void notifyWorktreeIncludeFileWriteFailed(Project project, VirtualFile root) {
        notifyError(project,
                    GitLocalize.notificationTitleWorktreeIncludeFileWriteFailed(),
                    GitLocalize.notificationContentWorktreeIncludeFileWriteFailed(root.getPath()));
    }

    public static void notifyConfigCopyFailed(Project project, List<Path> failedFiles) {
        notifyError(project,
                    GitLocalize.notificationTitleWorktreeConfigCopyFailed(),
                    GitLocalize.notificationContentWorktreeConfigCopyFailedFiles(
                        failedFiles.size(),
                        failedFiles.stream().map(Path::toString).collect(Collectors.joining("\n"))
                    ));
    }

    public static void notifyConfigCopyFailedUnexpectedly(Project project, Path targetWorktreeDir) {
        notifyError(project,
                    GitLocalize.notificationTitleWorktreeConfigCopyFailed(),
                    GitLocalize.notificationContentWorktreeConfigCopyFailedUnexpected(targetWorktreeDir.toString()));
    }

    public static void notifyAdditionalConfigCopyFailed(Project project, Path targetWorktreeDir) {
        notifyError(project,
                    GitLocalize.notificationTitleWorktreeAdditionalConfigCopyFailed(),
                    GitLocalize.notificationContentWorktreeAdditionalConfigCopyFailed(targetWorktreeDir.toString()));
    }

    public static void notifyAdditionalSettingsFailed(Project project, Path targetWorktreeDir) {
        notifyError(project,
                    GitLocalize.notificationTitleWorktreeAdditionalSettingsFailed(),
                    GitLocalize.notificationContentWorktreeAdditionalSettingsFailed(targetWorktreeDir.toString()));
    }

    public static void notifySetupScriptFailed(Project project, Path scriptPath) {
        notifyError(project,
                    GitLocalize.notificationTitleWorktreeSetupScriptFailed(),
                    GitLocalize.notificationContentWorktreeSetupScriptFailed(scriptPath.toString()));
    }

    public static void notifyWorktreeCreated(Project project, String branchName, Path targetWorktreeDir) {
        NotificationService.getInstance()
            .newInfo(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(GitLocalize.notificationTitleWorktreeCreated())
            .content(GitLocalize.notificationContentWorktreeCreated(branchName, targetWorktreeDir.toString()))
            .notify(project);
    }

    public static void notifyOpenProjectFailed(Project project, Path targetWorktreeDir) {
        notifyError(project,
                    GitLocalize.notificationTitleWorktreeOpenProjectFailed(),
                    GitLocalize.notificationContentWorktreeOpenProjectFailed(targetWorktreeDir.toString()));
    }

    private static void notifyError(Project project, LocalizeValue title, LocalizeValue content) {
        NotificationService.getInstance()
            .newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(title)
            .content(content)
            .notify(project);
    }

    private GitWorktreeNotifications() {
    }
}
