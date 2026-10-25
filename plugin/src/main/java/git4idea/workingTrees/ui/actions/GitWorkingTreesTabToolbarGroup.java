// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees.ui.actions;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionRef;
import consulo.ui.ex.action.AnSeparator;
import consulo.ui.ex.action.DefaultActionGroup;
import consulo.ui.ex.action.IdeActions;
import git4idea.workingTrees.actions.GitCreateWorkingTreeAction;

@ActionImpl(
    id = "Git.WorkingTrees.ToolwindowGroup.Toolbar",
    children = {
        @ActionRef(type = GitCreateWorkingTreeAction.class),
        @ActionRef(type = RefreshWorkingTreesAction.class),
        @ActionRef(type = RemoveWorkingTreeAction.class),
        @ActionRef(type = PruneWorkingTreesAction.class),
        @ActionRef(type = AnSeparator.class),
        @ActionRef(id = IdeActions.ACTION_CONTEXT_HELP)
    }
)
public class GitWorkingTreesTabToolbarGroup extends DefaultActionGroup {
}
