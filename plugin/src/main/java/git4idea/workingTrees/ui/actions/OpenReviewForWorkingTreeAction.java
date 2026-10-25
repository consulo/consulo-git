// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees.ui.actions;

import consulo.annotation.component.ActionImpl;
import consulo.git.icon.GitIconGroup;
import consulo.git.localize.GitLocalize;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.DumbAwareAction;
import git4idea.ui.branch.GitBranchReview;

@ActionImpl(id = "Git.WorkingTrees.OpenReview")
public class OpenReviewForWorkingTreeAction extends DumbAwareAction implements AnActionWithSyncUpdate {
    public OpenReviewForWorkingTreeAction() {
        super(GitLocalize.actionGitWorkingtreesOpenreviewText(), GitLocalize.actionGitWorkingtreesOpenreviewDescription(), GitIconGroup.pullrequestopen());
    }

    @Override
    public void update(AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(GitWorkingTreeTabActionsDataKeys.getSelectedReview(e) != null);
    }

    @RequiredUIAccess
    @Override
    public void actionPerformed(AnActionEvent e) {
        GitBranchReview review = GitWorkingTreeTabActionsDataKeys.getSelectedReview(e);
        if (review != null) {
            review.open();
        }
    }
}
