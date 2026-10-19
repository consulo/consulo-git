// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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

import consulo.dataContext.DataContext;
import consulo.git.localize.GitLocalize;
import consulo.ide.impl.idea.openapi.actionSystem.impl.SimpleDataContext;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.ex.action.ActionGroup;
import consulo.ui.ex.action.ActionPlaces;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.popup.JBPopupFactory;
import consulo.ui.ex.popup.PopupStep;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsBranchesTreeModel;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsBranchesTreePopupStepBase;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsRef;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsRepositoryModel;
import git4idea.GitBranch;
import git4idea.GitReference;
import git4idea.repo.GitRepository;
import git4idea.ui.branch.GitBranchPopupActions;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Generic counterpart of {@code com.intellij.vcs.git.branch.popup.GitDefaultBranchesPopupStep}
 * restricted to the single-repository case.
 *
 * @author VISTALL
 */
public class GitBranchesTreePopupStep extends DvcsBranchesTreePopupStepBase {
    private final GitDvcsRepositoryModel myGitRepository;
    private final List<Object> myActions;

    private GitBranchesTreeSingleRepoModel myTreeModel;
    private Runnable myFinalRunnable;

    public GitBranchesTreePopupStep(Project project, GitDvcsRepositoryModel repository, List<Object> actions) {
        // Single-repo popup: selectedRepository is null so ref-type labels stay plain ("Local"/"Remote")
        // instead of the "... in <repo>" form (matches GitDefaultBranchesPopupStep for repositories.size <= 1).
        super(project, null, List.<DvcsRepositoryModel>of(repository));
        myGitRepository = repository;
        myActions = actions;
        setTreeModel(createTreeModel(false));
    }

    @Override
    public DvcsBranchesTreeModel getTreeModel() {
        return myTreeModel;
    }

    @Override
    public LocalizeValue getSearchFieldEmptyText() {
        // Port of GitDefaultBranchesPopup.getSearchFiledEmptyText. GitVcsSettings.filterByActionInPopup
        // defaults to true; the repository filter only applies when multi-root, so in this
        // single-repository popup it is always off.
        boolean isActionFilterSelected = true;
        boolean isRepositoryFilterSelected = false;
        int separatorKind = isActionFilterSelected && isRepositoryFilterSelected ? 2 : 1;
        LocalizeValue byActions = isActionFilterSelected
            ? GitLocalize.gitBranchesPopupSearchFieldActionsEmptyText(separatorKind)
            : LocalizeValue.empty();
        LocalizeValue byRepository = isRepositoryFilterSelected
            ? GitLocalize.gitBranchesPopupSearchFieldRepositoriesEmptyText(separatorKind)
            : LocalizeValue.empty();
        return GitLocalize.gitBranchesPopupSearchFieldEmptyText(byActions, byRepository);
    }

    @Override
    protected DvcsBranchesTreeModel createTreeModel(boolean filterActive) {
        GitBranchesTreeSingleRepoModel model = new GitBranchesTreeSingleRepoModel(myProject, myGitRepository, myActions);
        model.init();
        return model;
    }

    @Override
    protected void setTreeModel(DvcsBranchesTreeModel treeModel) {
        myTreeModel = (GitBranchesTreeSingleRepoModel) treeModel;
    }

    @Override
    public PopupStep onChosen(Object selectedValue, boolean finalChoice) {
        DvcsRef ref = DvcsBranchesTreeModel.asRef(selectedValue);
        if (ref instanceof GitRefAdapter adapter) {
            ActionGroup group = createRefActionGroup(myProject, myGitRepository.repository(), adapter.reference());
            if (group != null) {
                return createActionsSubStep(group);
            }
        }

        if (selectedValue instanceof ActionGroup group && group.getTemplatePresentation().isEnabled()) {
            return createActionsSubStep(group);
        }

        if (selectedValue instanceof AnAction action) {
            myFinalRunnable = () -> invokeAction(action);
            return FINAL_CHOICE;
        }
        return FINAL_CHOICE;
    }

    @Nullable
    public static ActionGroup createRefActionGroup(Project project, GitRepository repository, @Nullable GitReference reference) {
        if (!(reference instanceof GitBranch branch)) {
            return null;
        }
        List<GitRepository> repositories = List.of(repository);
        return branch.isRemote()
            ? new GitBranchPopupActions.RemoteBranchActions(project, repositories, branch.getName(), repository)
            : new GitBranchPopupActions.LocalBranchActions(project, repositories, branch.getName(), repository);
    }

    private PopupStep createActionsSubStep(ActionGroup group) {
        DataContext dataContext = SimpleDataContext.getProjectContext(myProject);
        return JBPopupFactory.getInstance()
            .createActionsStep(group, dataContext, ActionPlaces.UNKNOWN, false, true, null, null, false, 0, false);
    }

    @Override
    public Runnable getFinalRunnable() {
        return myFinalRunnable;
    }
}
