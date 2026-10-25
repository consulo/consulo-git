// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees.ui.actions;

import consulo.ui.ex.action.AnActionEvent;
import consulo.versionControlSystem.workspace.VcsWorkspace;
import consulo.versionControlSystem.workspace.VcsWorkspaceDataKeys;
import git4idea.GitWorkingTree;
import git4idea.repo.GitRepository;
import git4idea.ui.branch.GitBranchReview;
import git4idea.workingTrees.ui.GitWorktreeGroup;
import git4idea.workingTrees.ui.GitWorktreeRow;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The git view of the workspace view's selection.
 */
public final class GitWorkingTreeTabActionsDataKeys {
    public static @Nullable List<GitWorkingTree> getSelectedWorkingTrees(AnActionEvent e) {
        List<VcsWorkspace> workspaces = e.getData(VcsWorkspaceDataKeys.SELECTED_WORKSPACES);
        if (workspaces == null) {
            return null;
        }
        List<GitWorkingTree> trees = new ArrayList<>();
        for (VcsWorkspace workspace : workspaces) {
            if (workspace instanceof GitWorktreeRow row) {
                trees.add(row.gitWorkingTree());
            }
        }
        return trees;
    }

    public static @Nullable GitRepository getCurrentRepository(AnActionEvent e) {
        return e.getData(VcsWorkspaceDataKeys.SELECTED_GROUP) instanceof GitWorktreeGroup group ? group.repository() : null;
    }

    public static @Nullable GitBranchReview getSelectedReview(AnActionEvent e) {
        List<VcsWorkspace> workspaces = e.getData(VcsWorkspaceDataKeys.SELECTED_WORKSPACES);
        if (workspaces == null || workspaces.size() != 1) {
            return null;
        }
        return workspaces.getFirst() instanceof GitWorktreeRow row ? row.review() : null;
    }

    private GitWorkingTreeTabActionsDataKeys() {
    }
}
