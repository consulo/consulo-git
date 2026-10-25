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
package git4idea.workingTrees.actions;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionParentRef;
import consulo.annotation.component.ActionRef;
import consulo.git.icon.GitIconGroup;
import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.versionControlSystem.change.ChangesViewContentManagerListener;
import consulo.versionControlSystem.ui.VcsShowToolWindowTabAction;
import git4idea.workingTrees.GitWorkingTreesService;
import git4idea.workingTrees.GitWorkingTreesUtil;
import git4idea.workingTrees.UnsupportedGitWorktreeSupportStatus;

@ActionImpl(
    id = "Git.Show.WorkingTrees",
    parents = @ActionParentRef(@ActionRef(id = "Vcs.Log.ToolWindow.TabActions.DropDown"))
)
public class ShowWorkingTreesAction extends DumbAwareAction implements AnActionWithSyncUpdate {
    public ShowWorkingTreesAction() {
        super(GitLocalize.actionGitShowWorkingtreesText(), GitLocalize.actionGitShowWorkingtreesDescription(), GitIconGroup.worktree());
    }

    @Override
    public void update(AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(canBeShown(e));
    }

    private static boolean canBeShown(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        if (project == null) {
            return false;
        }
        return !(GitWorkingTreesService.getWorktreeSupportStatus(project) instanceof UnsupportedGitWorktreeSupportStatus);
    }

    @RequiredUIAccess
    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        if (project == null) {
            return;
        }
        GitWorkingTreesService.getInstance(project).workingTreesTabOpenedByUser();
        project.getMessageBus().syncPublisher(ChangesViewContentManagerListener.class).toolWindowMappingChanged();
        project.getUIAccess().give(() -> VcsShowToolWindowTabAction.activateVcsTab(project, GitWorkingTreesUtil.TOOLWINDOW_TAB_ID, false));
    }
}
