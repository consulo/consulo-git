/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package git4idea.util;

import consulo.project.Project;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.event.NotificationListener;
import consulo.ui.ex.awt.DialogBuilder;
import consulo.ui.ex.awt.MultiLineLabel;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangesBrowserFactory;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import jakarta.annotation.Nonnull;

import javax.swing.event.HyperlinkEvent;
import java.util.Collection;
import java.util.List;

public class LocalChangesWouldBeOverwrittenHelper {

    @Nonnull
    private static String getErrorNotificationDescription() {
        return getErrorDescription(true);
    }

    @Nonnull
    private static String getErrorDialogDescription() {
        return getErrorDescription(false);
    }

    @Nonnull
    private static String getErrorDescription(boolean forNotification) {
        String line1 = "Your local changes would be overwritten by merge.";
        String line2 = "Commit, stash or revert them to proceed.";
        if (forNotification) {
            return line1 + "<br/>" + line2 + " <a href='view'>View them</a>";
        }
        else {
            return line1 + "\n" + line2;
        }
    }

    public static void showErrorNotification(@Nonnull final Project project,
                                             @Nonnull final VirtualFile root,
                                             @Nonnull final String operationName,
                                             @Nonnull final Collection<String> relativeFilePaths) {
        final Collection<String> absolutePaths = GitUtil.toAbsolute(root, relativeFilePaths);
        final List<Change> changes = GitUtil.findLocalChangesForPaths(project, root, absolutePaths, false);
        String notificationTitle = "Git " + StringUtil.capitalize(operationName) + " Failed";
        VcsNotifier.getInstance(project).notifyError(notificationTitle, getErrorNotificationDescription(), new NotificationListener.Adapter() {
            @Override
            protected void hyperlinkActivated(@Nonnull Notification notification, @Nonnull HyperlinkEvent e) {
                showErrorDialog(project, operationName, changes, absolutePaths);
            }
        });
    }

    public static void showErrorDialog(@Nonnull Project project,
                                       @Nonnull VirtualFile root,
                                       @Nonnull String operationName,
                                       @Nonnull Collection<String> relativeFilePaths) {
        Collection<String> absolutePaths = GitUtil.toAbsolute(root, relativeFilePaths);
        List<Change> changes = GitUtil.findLocalChangesForPaths(project, root, absolutePaths, false);
        showErrorDialog(project, operationName, changes, absolutePaths);
    }

    private static void showErrorDialog(@Nonnull Project project,
                                        @Nonnull String operationName,
                                        @Nonnull List<Change> changes,
                                        @Nonnull Collection<String> absolutePaths) {
        String title = "Local Changes Prevent from " + StringUtil.capitalize(operationName);
        String description = getErrorDialogDescription();
        if (changes.isEmpty()) {
            GitUtil.showPathsInDialog(project, absolutePaths, title, description);
        }
        else {
            DialogBuilder builder = new DialogBuilder(project);
            builder.setNorthPanel(new MultiLineLabel(description));
            ChangesBrowserFactory browserFactory = project.getApplication().getInstance(ChangesBrowserFactory.class);

            builder.setCenterPanel(browserFactory.createChangeBrowserWithRollback(project, changes).getComponent());
            builder.addOkAction();
            builder.setTitle(title);
            builder.show();
        }
    }

}
