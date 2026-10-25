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
package git4idea.workingTrees.ui.actions;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionRef;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.platform.base.localize.CommonLocalize;
import consulo.project.Project;
import consulo.ui.MessageBoxBuilder;
import consulo.ui.MessageButtonRole;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.ex.action.IdeActions;
import git4idea.GitWorkingTree;
import git4idea.repo.GitRepository;
import git4idea.workingTrees.GitCreateWorkingTreeService;
import git4idea.workingTrees.GitWorkingTreesService;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@ActionImpl(id = "Git.WorkingTrees.Remove", shortcutFrom = @ActionRef(id = IdeActions.ACTION_DELETE))
public class RemoveWorkingTreeAction extends DumbAwareAction implements AnActionWithSyncUpdate {
    public RemoveWorkingTreeAction() {
        super(GitLocalize.actionGitWorkingtreesRemoveText(), GitLocalize.actionGitWorkingtreesRemoveDescription(), PlatformIconGroup.generalDelete());
    }

    @Override
    public void update(AnActionEvent e) {
        List<GitWorkingTree> data = GitWorkingTreeTabActionsDataKeys.getSelectedWorkingTrees(e);
        GitRepository repository = GitWorkingTreeTabActionsDataKeys.getCurrentRepository(e);
        e.getPresentation().setEnabled(isEnabledFor(data, e.getData(Project.KEY), repository));
    }

    private static boolean isEnabledFor(@Nullable List<GitWorkingTree> trees, @Nullable Project project, @Nullable GitRepository repository) {
        if (project == null || repository == null || trees == null || trees.isEmpty()) {
            return false;
        }
        GitCreateWorkingTreeService creationService = GitCreateWorkingTreeService.getInstance();
        GitWorkingTreesService worktreesService = GitWorkingTreesService.getInstance(project);
        return trees.stream().allMatch(it ->
            !it.isCurrent() && !it.isMain() &&
                !creationService.isWorkingTreeCreationInProgress(it) &&
                !worktreesService.isWorkingTreeDeletionInProgress(it)
        );
    }

    @RequiredUIAccess
    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        List<GitWorkingTree> trees = GitWorkingTreeTabActionsDataKeys.getSelectedWorkingTrees(e);
        GitRepository repository = GitWorkingTreeTabActionsDataKeys.getCurrentRepository(e);
        if (project == null || repository == null || trees == null || !isEnabledFor(trees, project, repository)) {
            return;
        }

        showDeleteWorktreeDialog(trees).whenCompleteAsync((result, error) -> {
            if (Boolean.TRUE.equals(result)) {
                GitWorkingTreesService.getInstance(project).deleteWorkingTrees(project, trees, repository);
            }
        }, project.getUIAccess());
    }

    @RequiredUIAccess
    private static CompletableFuture<Boolean> showDeleteWorktreeDialog(List<GitWorkingTree> trees) {
        LocalizeValue message;
        LocalizeValue title;
        LocalizeValue yesText;
        if (trees.size() == 1) {
            message = GitLocalize.gitWorkingtreesDialogDeleteWorktreeMessage(trees.getFirst().getPath().getPresentableUrl());
            title = GitLocalize.gitWorkingtreesDialogDeleteWorktreeTitle();
            yesText = GitLocalize.gitWorkingtreesDialogDeleteWorktreeYesOption();
        }
        else {
            message = GitLocalize.gitWorkingtreesDialogDeleteWorktreesMessage(trees.size());
            title = GitLocalize.gitWorkingtreesDialogDeleteWorktreesTitle();
            yesText = GitLocalize.gitWorkingtreesDialogDeleteWorktreesYesOption();
        }

        return MessageBoxBuilder.<Boolean>create()
            .asQuestion()
            .title(title)
            .text(message)
            .button(MessageButtonRole.YES, yesText, Boolean.TRUE)
            .asDefaultButton()
            .button(MessageButtonRole.CANCEL, CommonLocalize.buttonCancel(), Boolean.FALSE)
            .asExitButton()
            .showAsync();
    }
}
