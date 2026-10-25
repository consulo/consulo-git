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
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.DumbAwareAction;
import git4idea.GitWorkingTree;
import git4idea.repo.GitRepository;
import git4idea.workingTrees.GitCreateWorkingTreeService;
import git4idea.workingTrees.GitWorkingTreesService;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A single action flipping between "Lock"/"Unlock" depending on the selected worktree's {@link GitWorkingTree#isLocked()}
 * state - disabled for the current/main worktree row and for a worktree still being created.
 */
@ActionImpl(id = "Git.WorkingTrees.ToggleLock")
public class GitToggleLockWorkingTreeAction extends DumbAwareAction implements AnActionWithSyncUpdate {
    public GitToggleLockWorkingTreeAction() {
        super(GitLocalize.actionGitWorkingtreesTogglelockText());
    }

    @Override
    public void update(AnActionEvent e) {
        List<GitWorkingTree> data = GitWorkingTreeTabActionsDataKeys.getSelectedWorkingTrees(e);
        GitRepository repository = GitWorkingTreeTabActionsDataKeys.getCurrentRepository(e);
        Boolean targetLockedState = resolveTargetLockedState(e.getData(Project.KEY), repository, data);
        e.getPresentation().setEnabled(targetLockedState != null);
        e.getPresentation().setText(Boolean.TRUE.equals(targetLockedState)
            ? GitLocalize.actionGitWorkingtreesUnlockText()
            : GitLocalize.actionGitWorkingtreesLockText());
    }

    // The action's own target state: locking an unlocked selection (false) or unlocking a locked one (true);
    // null when the selection is empty, mixed, or otherwise not eligible for either operation.
    private static @Nullable Boolean resolveTargetLockedState(@Nullable Project project,
                                                              @Nullable GitRepository repository,
                                                              @Nullable List<GitWorkingTree> trees) {
        if (project == null || repository == null || trees == null || trees.isEmpty()) {
            return null;
        }
        GitCreateWorkingTreeService creationService = GitCreateWorkingTreeService.getInstance();
        if (trees.stream().anyMatch(it -> it.isCurrent() || it.isMain() || creationService.isWorkingTreeCreationInProgress(it))) {
            return null;
        }
        List<Boolean> states = trees.stream().map(GitWorkingTree::isLocked).distinct().toList();
        return states.size() == 1 ? states.getFirst() : null;
    }

    @RequiredUIAccess
    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        List<GitWorkingTree> data = GitWorkingTreeTabActionsDataKeys.getSelectedWorkingTrees(e);
        GitRepository repository = GitWorkingTreeTabActionsDataKeys.getCurrentRepository(e);
        if (project == null || data == null || repository == null) {
            return;
        }
        Boolean targetLockedState = resolveTargetLockedState(project, repository, data);
        if (targetLockedState == null || data.size() != 1) {
            return;
        }
        GitWorkingTree tree = data.getFirst();

        if (targetLockedState) {
            GitWorkingTreesService.getInstance(project).unlockWorkingTree(project, repository, tree);
        }
        else {
            GitWorkingTreesService.getInstance(project).lockWorkingTree(project, repository, tree);
        }
    }
}
