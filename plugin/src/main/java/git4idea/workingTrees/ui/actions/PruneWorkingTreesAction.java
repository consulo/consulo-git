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
import consulo.platform.base.localize.CommonLocalize;
import consulo.project.Project;
import consulo.ui.MessageBoxBuilder;
import consulo.ui.MessageButtonRole;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.DumbAwareAction;
import git4idea.GitWorkingTree;
import git4idea.repo.GitRepository;
import git4idea.workingTrees.GitWorkingTreesService;

import java.util.List;

@ActionImpl(id = "Git.WorkingTrees.Prune")
public class PruneWorkingTreesAction extends DumbAwareAction implements AnActionWithSyncUpdate {
    public PruneWorkingTreesAction() {
        super(GitLocalize.actionGitWorkingtreesPruneText(), GitLocalize.actionGitWorkingtreesPruneDescription(), PlatformIconGroup.actionsClearcash());
    }

    @Override
    public void update(AnActionEvent e) {
        GitRepository repository = GitWorkingTreeTabActionsDataKeys.getCurrentRepository(e);
        e.getPresentation().setEnabled(repository != null && isEnabledFor(repository));
    }

    private static boolean isEnabledFor(GitRepository repository) {
        return repository.getWorkingTreeHolder().getWorkingTrees().stream().anyMatch(GitWorkingTree::isPrunable);
    }

    @RequiredUIAccess
    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        GitRepository repository = GitWorkingTreeTabActionsDataKeys.getCurrentRepository(e);
        if (project == null || repository == null) {
            return;
        }
        List<String> prunableWorktrees = repository.getWorkingTreeHolder().getWorkingTrees().stream()
            .filter(GitWorkingTree::isPrunable)
            .map(it -> it.getPath().getName())
            .toList();
        if (prunableWorktrees.isEmpty()) {
            return;
        }

        MessageBoxBuilder.<Boolean>create()
            .asQuestion()
            .title(GitLocalize.gitWorkingtreesDialogPruneWorktreeTitle())
            .text(GitLocalize.gitWorkingtreesDialogPruneWorktreeMessage(String.join(", ", prunableWorktrees)))
            .button(MessageButtonRole.YES, GitLocalize.gitWorkingtreesDialogPruneWorktreeYesOption(), Boolean.TRUE)
            .asDefaultButton()
            .button(MessageButtonRole.CANCEL, CommonLocalize.buttonCancel(), Boolean.FALSE)
            .asExitButton()
            .showAsync()
            .whenCompleteAsync((result, error) -> {
                if (Boolean.TRUE.equals(result)) {
                    GitWorkingTreesService.getInstance(project).pruneWorkingTrees(project, repository);
                }
            }, project.getUIAccess());
    }
}
