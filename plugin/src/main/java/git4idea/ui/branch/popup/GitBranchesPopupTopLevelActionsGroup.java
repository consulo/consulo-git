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
package git4idea.ui.branch.popup;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionRef;
import consulo.application.dumb.DumbAware;
import consulo.ui.ex.action.DefaultActionGroup;

@ActionImpl(
    id = GitBranchesPopupTopLevelActionsGroup.ID,
    children = {
        @ActionRef(id = "Vcs.UpdateProject"),
        @ActionRef(id = "CheckinProject"),
        @ActionRef(id = "Vcs.Push")
    }
)
public class GitBranchesPopupTopLevelActionsGroup extends DefaultActionGroup implements DumbAware {
    public static final String ID = "Git.Experimental.Branch.Popup.Actions";

    public GitBranchesPopupTopLevelActionsGroup() {
        setPopup(false);
    }
}
