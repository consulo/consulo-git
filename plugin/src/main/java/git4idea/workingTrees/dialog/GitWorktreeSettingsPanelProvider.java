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
package git4idea.workingTrees.dialog;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.layout.VerticalLayout;

/**
 * Contributes a settings section to the New Worktree dialog. Register an implementation to let the user
 * configure something else about the new worktree, such as a bigger settings panel of your own.
 */
@ExtensionAPI(ComponentScope.APPLICATION)
public interface GitWorktreeSettingsPanelProvider {
    /**
     * Adds this provider's UI to {@code panel} and returns a handle that applies the user's choices once the
     * worktree is created.
     */
    @RequiredUIAccess
    GitWorktreeSettingsPanelHandle createPanel(VerticalLayout panel, Project project);
}
