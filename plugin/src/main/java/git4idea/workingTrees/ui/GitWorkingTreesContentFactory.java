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
package git4idea.workingTrees.ui;

import consulo.annotation.component.ExtensionImpl;
import consulo.project.Project;
import consulo.versionControlSystem.workspace.VcsWorkspaceContentFactory;
import consulo.versionControlSystem.workspace.VcsWorkspaceProvider;
import jakarta.inject.Inject;

@ExtensionImpl
public class GitWorkingTreesContentFactory extends VcsWorkspaceContentFactory {
    public static final String GIT_WORKING_TREE_TOOLWINDOW_TAB_TOOLBAR = "GitWorkingTreeToolWindowTabToolbar";
    public static final String GIT_WORKING_TREE_TOOLWINDOW_TAB_EMPTY_LIST = "GitWorkingTreeToolWindowTabEmptyList";

    public static final String EMPTY_TAB_WORKING_TREE_CONCEPT_HELP_ID = "worktree-concept";
    public static final String TOOLWINDOW_CONTENT_HELP_ID = "worktree-help";

    @Inject
    public GitWorkingTreesContentFactory(Project project) {
        super(project);
    }

    @Override
    protected VcsWorkspaceProvider getProvider() {
        return GitWorkingTreesWorkspaceProvider.getInstance(myProject);
    }
}
