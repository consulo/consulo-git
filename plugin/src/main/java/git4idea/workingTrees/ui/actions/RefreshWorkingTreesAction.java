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
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.ex.action.IdeActions;
import git4idea.repo.GitRepository;
import git4idea.workingTrees.GitWorkingTreesService;
import git4idea.workingTrees.GitWorktreeSupportStatus;
import git4idea.workingTrees.MultipleRepositoryGitWorktreeSupportStatus;
import git4idea.workingTrees.SingleRepositoryGitWorktreeSupportStatus;

import java.util.List;

@ActionImpl(id = "Git.WorkingTrees.Refresh", shortcutFrom = @ActionRef(id = IdeActions.ACTION_SYNCHRONIZE))
public class RefreshWorkingTreesAction extends DumbAwareAction implements AnActionWithSyncUpdate {
    public RefreshWorkingTreesAction() {
        super(GitLocalize.actionGitWorkingtreesRefreshText(), GitLocalize.actionGitWorkingtreesRefreshDescription(), PlatformIconGroup.actionsRefresh());
    }

    @Override
    public void update(AnActionEvent e) {
        e.getPresentation().setEnabled(!repositoriesToRefresh(e).isEmpty());
    }

    @RequiredUIAccess
    @Override
    public void actionPerformed(AnActionEvent e) {
        // Refresh the whole tab: reload every repository's worktrees, regardless of the current selection.
        repositoriesToRefresh(e).forEach(it -> it.getWorkingTreeHolder().scheduleReload());
    }

    private static List<GitRepository> repositoriesToRefresh(AnActionEvent e) {
        GitWorktreeSupportStatus status = GitWorkingTreesService.getWorktreeSupportStatus(e.getData(Project.KEY));
        return switch (status) {
            case SingleRepositoryGitWorktreeSupportStatus single -> List.of(single.repository());
            case MultipleRepositoryGitWorktreeSupportStatus multiple -> multiple.repositories();
            default -> List.of();
        };
    }
}
