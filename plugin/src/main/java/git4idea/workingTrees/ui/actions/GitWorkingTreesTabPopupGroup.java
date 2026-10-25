// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees.ui.actions;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionRef;
import consulo.ui.ex.action.AnSeparator;
import consulo.ui.ex.action.DefaultActionGroup;

@ActionImpl(
    id = "Git.WorkingTrees.ToolwindowGroup.Popup",
    children = {
        @ActionRef(type = OpenWorkingTreeAction.class),
        @ActionRef(type = OpenReviewForWorkingTreeAction.class),
        @ActionRef(type = GitToggleLockWorkingTreeAction.class),
        @ActionRef(type = AnSeparator.class),
        @ActionRef(type = RemoveWorkingTreeAction.class)
    }
)
public class GitWorkingTreesTabPopupGroup extends DefaultActionGroup {
}
