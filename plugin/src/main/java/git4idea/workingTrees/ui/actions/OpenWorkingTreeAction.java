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
import consulo.git.localize.GitLocalize;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.DumbAwareAction;
import git4idea.GitWorkingTree;
import git4idea.workingTrees.GitCreateWorkingTreeService;
import git4idea.workingTrees.GitWorkingTreesService;
import org.jspecify.annotations.Nullable;

import java.util.List;

@ActionImpl(id = "Git.WorkingTrees.Open")
public class OpenWorkingTreeAction extends DumbAwareAction implements AnActionWithSyncUpdate {
    public OpenWorkingTreeAction() {
        super(GitLocalize.actionGitWorkingtreesOpenText(), GitLocalize.actionGitWorkingtreesOpenDescription(), PlatformIconGroup.actionsMenu_open());
    }

    @Override
    public void update(AnActionEvent e) {
        List<GitWorkingTree> data = GitWorkingTreeTabActionsDataKeys.getSelectedWorkingTrees(e);
        e.getPresentation().setEnabled(isEnabledFor(data, e.getData(Project.KEY)));
    }

    private static boolean isEnabledFor(@Nullable List<GitWorkingTree> trees, @Nullable Project project) {
        if (project == null || trees == null || trees.size() != 1 || trees.getFirst().isCurrent()) {
            return false;
        }
        GitWorkingTree workingTree = trees.getFirst();
        return !GitCreateWorkingTreeService.getInstance().isWorkingTreeCreationInProgress(workingTree) &&
            !GitWorkingTreesService.getInstance(project).isWorkingTreeDeletionInProgress(workingTree) &&
            !workingTree.isPrunable();
    }

    @RequiredUIAccess
    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        if (project == null) {
            return;
        }
        open(project, GitWorkingTreeTabActionsDataKeys.getSelectedWorkingTrees(e));
    }

    @RequiredUIAccess
    public static void open(Project project, @Nullable List<GitWorkingTree> trees) {
        if (!isEnabledFor(trees, project)) {
            return;
        }
        GitWorkingTreesService.getInstance(project).openWorkingTreeProject(trees.getFirst());
    }
}
