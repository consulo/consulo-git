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

import consulo.annotation.component.ExtensionImpl;
import consulo.project.Project;
import consulo.ui.ex.action.ActionManager;
import consulo.versionControlSystem.distributed.repository.Repository;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsBranchesTreePopupModel;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsBranchesTreePopupModelFactory;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;

/**
 * @author VISTALL
 * @since 2026-10-19
 */
@ExtensionImpl
public class GitBranchesTreePopupModelFactory implements DvcsBranchesTreePopupModelFactory {
    private final Project myProject;
    private final ActionManager myActionManager;

    @Inject
    public GitBranchesTreePopupModelFactory(Project project, ActionManager actionManager) {
        myProject = project;
        myActionManager = actionManager;
    }

    @Nullable
    @Override
    public DvcsBranchesTreePopupModel<?> create(Repository repository) {
        return repository instanceof GitRepository gitRepository
            ? new GitBranchesTreePopupModel(myActionManager, gitRepository, myProject)
            : null;
    }
}
