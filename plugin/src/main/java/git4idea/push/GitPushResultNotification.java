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
package git4idea.push;

import consulo.application.ApplicationManager;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.NotificationGroup;
import consulo.project.ui.notification.NotificationType;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.ui.UpdateInfoTree;
import consulo.versionControlSystem.ui.ViewUpdateInfoNotification;
import consulo.versionControlSystem.update.ActionInfo;
import consulo.versionControlSystem.update.UpdatedFiles;
import git4idea.branch.GitBranchUtil;
import git4idea.repo.GitRepository;
import git4idea.update.GitUpdateResult;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;

class GitPushResultNotification extends Notification {
    public static final LocalizeValue VIEW_FILES_UPDATED_DURING_THE_PUSH = LocalizeValue.localizeTODO("View files updated during the push");

    public static final String UPDATE_WITH_RESOLVED_CONFLICTS =
        "push has been cancelled, because there were conflicts during update.<br/>" + "Check that conflicts were resolved correctly, and " +
            "invoke push again.";
    public static final String INCOMPLETE_UPDATE =
        "push has been cancelled, because not all conflicts were resolved during update.<br/>" + "Resolve the conflicts and " +
            "invoke push again.";
    public static final String UPDATE_WITH_ERRORS = "push was rejected, and update failed with error.";
    public static final String UPDATE_CANCELLED = "push was rejected, and update was cancelled.";

    private static final Logger LOG = Logger.getInstance(GitPushResultNotification.class);

    public GitPushResultNotification(@Nonnull NotificationGroup groupDisplayId,
                                     @Nonnull String title,
                                     @Nonnull String content,
                                     @Nonnull NotificationType type) {
        super(groupDisplayId, title, content, type);
    }

    @Nonnull
    static GitPushResultNotification create(@Nonnull Project project, @Nonnull GitPushResult pushResult, boolean multiRepoProject) {
        GroupedPushResult grouped = GroupedPushResult.group(pushResult.getResults());

        String title;
        NotificationType type;
        if (!grouped.errors.isEmpty()) {
            if (!grouped.successful.isEmpty()) {
                title = "Push partially failed";
            }
            else {
                title = "Push failed";
            }
            type = NotificationType.ERROR;
        }
        else if (!grouped.rejected.isEmpty() || !grouped.customRejected.isEmpty()) {
            if (!grouped.successful.isEmpty()) {
                title = "Push partially rejected";
            }
            else {
                title = "Push rejected";
            }
            type = NotificationType.WARNING;
        }
        else {
            title = "Push successful";
            type = NotificationType.INFORMATION;
        }

        String description = formDescription(pushResult.getResults(), multiRepoProject);

        NotificationGroup group =
            type == NotificationType.INFORMATION ? VcsNotifier.STANDARD_NOTIFICATION : VcsNotifier.IMPORTANT_ERROR_NOTIFICATION;

        GitPushResultNotification notification = new GitPushResultNotification(group, title, description, type);

        UpdatedFiles updatedFiles = pushResult.getUpdatedFiles();
        if (!updatedFiles.isEmpty()) {
            ApplicationManager.getApplication().invokeLater(() -> {
                UpdateInfoTree tree = ProjectLevelVcsManager.getInstance(project)
                    .showUpdateProjectInfo(updatedFiles,
                        "Update",
                        ActionInfo.UPDATE,
                        false);
                if (tree != null) {
                    tree.setBefore(pushResult.getBeforeUpdateLabel());
                    tree.setAfter(pushResult.getAfterUpdateLabel());
                    notification.addAction(new ViewUpdateInfoNotification(project,
                        tree,
                        VIEW_FILES_UPDATED_DURING_THE_PUSH,
                        notification));
                }
            });
        }
        return notification;
    }

    private static String formDescription(@Nonnull Map<GitRepository, GitPushRepoResult> results, final boolean multiRepoProject) {
        List<Map.Entry<GitRepository, GitPushRepoResult>> entries = ContainerUtil.sorted(results.entrySet(), (o1, o2) ->
        {
            // successful first
            int compareResultTypes = GitPushRepoResult.TYPE_COMPARATOR.compare(o1.getValue().getType(), o2.getValue().getType());
            if (compareResultTypes != 0) {
                return compareResultTypes;
            }
            return DvcsUtil.REPOSITORY_COMPARATOR.compare(o1.getKey(), o2.getKey());
        });

        return StringUtil.join(entries, entry ->
        {
            GitRepository repository = entry.getKey();
            GitPushRepoResult result = entry.getValue();

            String description = formRepoDescription(result);
            if (!multiRepoProject) {
                description = StringUtil.capitalize(description);
            }
            else {
                description = DvcsUtil.getShortRepositoryName(repository) + ": " + description;
            }
            return description;
        }, "<br/>");
    }

    private static String formRepoDescription(@Nonnull GitPushRepoResult result) {
        String description;
        String sourceBranch = GitBranchUtil.stripRefsPrefix(result.getSourceBranch());
        String targetBranch = GitBranchUtil.stripRefsPrefix(result.getTargetBranch());
        String tagDescription = formTagDescription(result.getPushedTags(), result.getTargetRemote());
        switch (result.getType()) {
            case SUCCESS:
                int commitNum = result.getNumberOfPushedCommits();
                String commits = StringUtil.pluralize("commit", commitNum);
                description = String.format("pushed %d %s to %s", commitNum, commits, targetBranch);
                if (tagDescription != null) {
                    description += ", and " + tagDescription;
                }
                break;
            case NEW_BRANCH:
                description = String.format("pushed %s to new branch %s", sourceBranch, targetBranch);
                if (tagDescription != null) {
                    description += ", and " + tagDescription;
                }
                break;
            case UP_TO_DATE:
                if (tagDescription != null) {
                    description = "pushed " + tagDescription;
                }
                else {
                    description = "everything is up-to-date";
                }
                break;
            case FORCED:
                description = String.format("force pushed %s to %s", sourceBranch, targetBranch);
                break;
            case REJECTED_NO_FF:
                description = formDescriptionBasedOnUpdateResult(result.getUpdateResult(), targetBranch);
                break;
            case REJECTED_OTHER:
                description = String.format("push %s to %s was rejected by remote", sourceBranch, targetBranch);
                break;
            case ERROR:
                description = "failed with error: " + result.getError();
                break;
            default:
                LOG.error("Unexpected push result: " + result);
                description = "";
                break;
        }
        return description;
    }

    @Nullable
    private static String formTagDescription(@Nonnull List<String> pushedTags, @Nonnull String remoteName) {
        if (pushedTags.isEmpty()) {
            return null;
        }
        if (pushedTags.size() == 1) {
            return "tag " + GitBranchUtil.stripRefsPrefix(pushedTags.get(0)) + " to " + remoteName;
        }
        return pushedTags.size() + " tags to " + remoteName;
    }

    private static String formDescriptionBasedOnUpdateResult(GitUpdateResult updateResult, String targetBranch) {
        if (updateResult == null || updateResult == GitUpdateResult.SUCCESS || updateResult == GitUpdateResult.NOTHING_TO_UPDATE) {
            return String.format("push to %s was rejected", targetBranch);
        }
        else if (updateResult == GitUpdateResult.SUCCESS_WITH_RESOLVED_CONFLICTS) {
            return UPDATE_WITH_RESOLVED_CONFLICTS;
        }
        else if (updateResult == GitUpdateResult.INCOMPLETE) {
            return INCOMPLETE_UPDATE;
        }
        else if (updateResult == GitUpdateResult.CANCEL) {
            return UPDATE_CANCELLED;
        }
        else {
            return UPDATE_WITH_ERRORS;
        }
    }
}
