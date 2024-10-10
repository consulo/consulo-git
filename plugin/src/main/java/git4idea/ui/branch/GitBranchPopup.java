/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package git4idea.ui.branch;

import consulo.ide.impl.idea.dvcs.branch.DvcsBranchPopup;
import consulo.ide.impl.idea.dvcs.ui.BranchActionGroup;
import consulo.ide.impl.idea.dvcs.ui.LightActionGroup;
import consulo.ide.impl.idea.dvcs.ui.RootAction;
import consulo.project.Project;
import consulo.ui.ex.action.ActionGroup;
import consulo.ui.ex.action.AnAction;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.function.Condition;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.repository.AbstractRepositoryManager;
import git4idea.GitUtil;
import git4idea.branch.GitBranchUtil;
import git4idea.config.GitVcsSettings;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Objects;

import static consulo.ide.impl.idea.dvcs.branch.DvcsBranchPopup.MyMoreIndex.DEFAULT_REPO_NUM;
import static consulo.ide.impl.idea.dvcs.branch.DvcsBranchPopup.MyMoreIndex.MAX_REPO_NUM;
import static consulo.ide.impl.idea.dvcs.ui.BranchActionGroupPopup.wrapWithMoreActionIfNeeded;
import static consulo.ide.impl.idea.dvcs.ui.BranchActionUtil.*;
import static consulo.util.collection.ContainerUtil.map;
import static java.util.stream.Collectors.toList;

/**
 * The popup which allows to quickly switch and control Git branches.
 * <p>
 */
class GitBranchPopup extends DvcsBranchPopup<GitRepository> {
    private static final String DIMENSION_SERVICE_KEY = "Git.Branch.Popup";
    static final String SHOW_ALL_LOCALS_KEY = "Git.Branch.Popup.ShowAllLocals";
    static final String SHOW_ALL_REMOTES_KEY = "Git.Branch.Popup.ShowAllRemotes";
    static final String SHOW_ALL_REPOSITORIES = "Git.Branch.Popup.ShowAllRepositories";

    /**
     * @param currentRepository Current repository, which means the repository of the currently open or selected file.
     *                          In the case of synchronized branch operations current repository matter much less, but sometimes is used,
     *                          for example, it is preselected in the repositories combobox in the compare branches dialog.
     */
    static GitBranchPopup getInstance(@Nonnull final Project project, @Nonnull GitRepository currentRepository) {
        final GitVcsSettings vcsSettings = GitVcsSettings.getInstance(project);
        Condition<AnAction> preselectActionCondition = action -> {
            if (action instanceof GitBranchPopupActions.LocalBranchActions) {
                GitBranchPopupActions.LocalBranchActions branchAction = (GitBranchPopupActions.LocalBranchActions)action;
                String branchName = branchAction.getBranchName();

                String recentBranch;
                List<GitRepository> repositories = branchAction.getRepositories();
                if (repositories.size() == 1) {
                    recentBranch = vcsSettings.getRecentBranchesByRepository().get(repositories.iterator().next().getRoot().getPath());
                }
                else {
                    recentBranch = vcsSettings.getRecentCommonBranch();
                }

                if (recentBranch != null && recentBranch.equals(branchName)) {
                    return true;
                }
            }
            return false;
        };
        return new GitBranchPopup(currentRepository, GitUtil.getRepositoryManager(project), vcsSettings, preselectActionCondition);
    }

    private GitBranchPopup(
        @Nonnull GitRepository currentRepository,
        @Nonnull GitRepositoryManager repositoryManager,
        @Nonnull GitVcsSettings vcsSettings,
        @Nonnull Condition<AnAction> preselectActionCondition
    ) {
        super(
            currentRepository,
            repositoryManager,
            new GitMultiRootBranchConfig(repositoryManager.getRepositories()),
            vcsSettings,
            preselectActionCondition,
            DIMENSION_SERVICE_KEY
        );
    }

    @Override
    protected void fillWithCommonRepositoryActions(
        @Nonnull LightActionGroup popupGroup,
        @Nonnull AbstractRepositoryManager<GitRepository> repositoryManager
    ) {
        List<GitRepository> allRepositories = repositoryManager.getRepositories();
        popupGroup.add(new GitBranchPopupActions.GitNewBranchAction(myProject, allRepositories));
        popupGroup.add(new GitBranchPopupActions.CheckoutRevisionActions(myProject, allRepositories));

        popupGroup.addAll(createRepositoriesActions());

        popupGroup.addSeparator("Common Local Branches");
        List<BranchActionGroup> localBranchActions = myMultiRootBranchConfig.getLocalBranchNames()
            .stream()
            .map(l -> createLocalBranchActions(allRepositories, l))
            .filter(Objects::nonNull)
            .collect(toList());
        wrapWithMoreActionIfNeeded(
            myProject,
            popupGroup,
            ContainerUtil.sorted(localBranchActions, FAVORITE_BRANCH_COMPARATOR),
            getNumOfTopShownBranches(localBranchActions),
            SHOW_ALL_LOCALS_KEY
        );
        popupGroup.addSeparator("Common Remote Branches");
        List<BranchActionGroup> remoteBranchActions = map(
            ((GitMultiRootBranchConfig)myMultiRootBranchConfig).getRemoteBranches(),
            remoteBranch -> new GitBranchPopupActions.RemoteBranchActions(myProject, allRepositories, remoteBranch, myCurrentRepository)
        );
        wrapWithMoreActionIfNeeded(
            myProject,
            popupGroup,
            ContainerUtil.sorted(remoteBranchActions, FAVORITE_BRANCH_COMPARATOR),
            getNumOfFavorites(remoteBranchActions),
            SHOW_ALL_REMOTES_KEY
        );
    }

    @Nullable
    private GitBranchPopupActions.LocalBranchActions createLocalBranchActions(
        @Nonnull List<GitRepository> allRepositories,
        @Nonnull String branch
    ) {
        List<GitRepository> repositories = filterRepositoriesNotOnThisBranch(branch, allRepositories);
        return repositories.isEmpty() ? null : new GitBranchPopupActions.LocalBranchActions(
            myProject,
            repositories,
            branch,
            myCurrentRepository
        );
    }

    @Nonnull
    @Override
    protected LightActionGroup createRepositoriesActions() {
        LightActionGroup popupGroup = new LightActionGroup();
        popupGroup.addSeparator("Repositories");
        List<ActionGroup> rootActions = DvcsUtil.sortRepositories(myRepositoryManager.getRepositories())
            .stream()
            .map(repo -> new RootAction<>(
                repo,
                highlightCurrentRepo() ? myCurrentRepository : null,
                new GitBranchPopupActions(repo.getProject(), repo).createActions(),
                GitBranchUtil.getDisplayableBranchText(repo)
            ))
            .collect(toList());
        wrapWithMoreActionIfNeeded(
            myProject,
            popupGroup,
            rootActions,
            rootActions.size() > MAX_REPO_NUM ? DEFAULT_REPO_NUM : MAX_REPO_NUM,
            SHOW_ALL_REPOSITORIES
        );
        return popupGroup;
    }

    @Override
    protected void fillPopupWithCurrentRepositoryActions(@Nonnull LightActionGroup popupGroup, @Nullable LightActionGroup actions) {
        popupGroup.addAll(new GitBranchPopupActions(myCurrentRepository.getProject(), myCurrentRepository).createActions(
            actions,
            myRepoTitleInfo,
            true
        ));
    }
}
