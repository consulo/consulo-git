// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.ignore.actions;

import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.DumbAwareAction;
import git4idea.GitUtil;
import git4idea.ignore.lang.GitExcludeFileType;

public abstract class DefaultGitExcludeAction extends DumbAwareAction implements AnActionWithSyncUpdate {
    protected DefaultGitExcludeAction(LocalizeValue text, LocalizeValue description) {
        super(text, description, GitExcludeFileType.INSTANCE.getIcon());
    }

    @Override
    public void update(AnActionEvent e) {
        boolean enabled = isEnabled(e);
        e.getPresentation().setVisible(enabled);
        e.getPresentation().setEnabled(enabled);
    }

    protected boolean isEnabled(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        return project != null && !GitUtil.getRepositories(project).isEmpty();
    }
}
