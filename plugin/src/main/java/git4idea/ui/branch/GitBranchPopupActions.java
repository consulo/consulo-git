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

import consulo.git.localize.GitBranchesLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.InputBoxBuilder;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.*;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.branch.BranchActionGroup;
import consulo.versionControlSystem.distributed.branch.BranchActionUtil;
import consulo.versionControlSystem.distributed.branch.NewBranchAction;
import consulo.versionControlSystem.distributed.branch.PopupElementWithAdditionalInfo;
import consulo.versionControlSystem.distributed.repository.Repository;
import git4idea.branch.GitBranchUtil;
import git4idea.branch.GitBrancher;
import git4idea.repo.GitRepository;
import git4idea.validators.GitNewBranchNameValidator;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.List;

import static git4idea.GitStatisticsCollectorKt.reportUsage;
import static git4idea.branch.GitBranchType.LOCAL;
import static git4idea.branch.GitBranchType.REMOTE;
import static java.util.stream.Collectors.toList;

public class GitBranchPopupActions {
    private final Project myProject;
    private final GitRepository myRepository;

    GitBranchPopupActions(Project project, GitRepository repository) {
        myProject = project;
        myRepository = repository;
    }

    ActionGroup createActions() {
        return createActions(null, null, false);
    }

    ActionGroup createActions(@Nullable ActionGroup toInsert, @Nullable GitRepository specificRepository, boolean firstLevelGroup) {
        ActionGroup.Builder popupGroup = ActionGroup.newImmutableBuilder();
        List<GitRepository> repositoryList = List.of(myRepository);

        popupGroup.add(new GitNewBranchAction(myProject, repositoryList));
        popupGroup.add(new CheckoutRevisionActions(myProject, repositoryList));

        if (toInsert != null) {
            popupGroup.addAll(toInsert);
        }

        popupGroup.addSeparator(
            specificRepository == null
                ? GitBranchesLocalize.actionLocalBranchesText()
                : GitBranchesLocalize.actionLocalBranchesInRepoText(DvcsUtil.getShortRepositoryName(specificRepository))
        );
        List<BranchActionGroup> localBranchActions = myRepository.getBranches()
            .getLocalBranches()
            .stream()
            .sorted()
            .filter(branch -> !branch.equals(myRepository.getCurrentBranch()))
            .map(branch -> new LocalBranchActions(myProject, repositoryList, branch.getName(), myRepository))
            .collect(toList());
        // if there are only a few local favorites -> show all;  for remotes it's better to show only favorites;
        BranchActionUtil.wrapWithMoreActionIfNeeded(
            myProject,
            popupGroup,
            ContainerUtil.sorted(localBranchActions, BranchActionUtil.FAVORITE_BRANCH_COMPARATOR),
            BranchActionUtil.getNumOfTopShownBranches(localBranchActions),
            firstLevelGroup ? GitBranchPopup.SHOW_ALL_LOCALS_KEY : null,
            firstLevelGroup
        );

        popupGroup.addSeparator(
            specificRepository == null
                ? GitBranchesLocalize.actionRemoteBranchesText()
                : GitBranchesLocalize.actionRemoteBranchesInRepoText(DvcsUtil.getShortRepositoryName(specificRepository))
        );
        List<BranchActionGroup> remoteBranchActions =
            myRepository.getBranches()
                .getRemoteBranches()
                .stream()
                .sorted()
                .map(remoteBranch -> new RemoteBranchActions(
                    myProject,
                    repositoryList,
                    remoteBranch.getName(),
                    myRepository
                ))
                .collect(toList());
        BranchActionUtil.wrapWithMoreActionIfNeeded(
            myProject,
            popupGroup,
            ContainerUtil.sorted(remoteBranchActions, BranchActionUtil.FAVORITE_BRANCH_COMPARATOR),
            BranchActionUtil.getNumOfFavorites(remoteBranchActions),
            firstLevelGroup ? GitBranchPopup.SHOW_ALL_REMOTES_KEY : null
        );
        return popupGroup.build();
    }

    public static class GitNewBranchAction extends NewBranchAction<GitRepository> {
        public GitNewBranchAction(@Nonnull Project project, @Nonnull List<GitRepository> repositories) {
            super(project, repositories);
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(@Nonnull AnActionEvent e) {
            GitBranchUtil.getNewBranchNameFromUser(myProject, myRepositories, GitBranchesLocalize.dialogTitleCreateNewBranch())
                .whenCompleteAsync((name, error) -> {
                    if (name != null) {
                        GitBrancher brancher = myProject.getInstance(GitBrancher.class);
                        brancher.checkoutNewBranch(name, myRepositories);
                        reportUsage("git.branch.create.new");
                    }
                }, UIAccess.current());
        }
    }

    /**
     * Checkout manually entered tag or revision number.
     */
    public static class CheckoutRevisionActions extends DumbAwareAction implements AnActionWithSyncUpdate {
        private final Project myProject;
        private final List<GitRepository> myRepositories;

        public CheckoutRevisionActions(Project project, List<GitRepository> repositories) {
            super(GitBranchesLocalize.actionCheckoutTagOrRevisionText());
            myProject = project;
            myRepositories = repositories;
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(@Nonnull AnActionEvent e) {
            // TODO autocomplete branches, tags.
            // on type check ref validity, on OK check ref existence.
            InputBoxBuilder.text()
                .title(GitBranchesLocalize.dialogTitleCheckout())
                .text(GitBranchesLocalize.dialogMessageEnterReferenceBranchTagNameOrCommitHash())
                .showAsync(myProject)
                .whenCompleteAsync((reference, error) -> {
                    if (reference != null) {
                        GitBrancher brancher = myProject.getInstance(GitBrancher.class);
                        brancher.checkout(reference, true, myRepositories, null);
                        reportUsage("git.branch.checkout.revision");
                    }
                }, UIAccess.current());
        }

        @Override
        public void update(@Nonnull AnActionEvent e) {
            boolean isFresh = ContainerUtil.and(myRepositories, Repository::isFresh);
            if (isFresh) {
                e.getPresentation().setEnabled(false);
                e.getPresentation().setDescription(GitBranchesLocalize.actionCheckoutTagOrRevisionImpossibleDescription());
            }
        }
    }

    /**
     * Actions available for local branches.
     */
    private abstract static class AbstractBranchActions extends BranchActionGroup {
        @Nonnull
        protected final Project myProject;
        @Nonnull
        protected final List<GitRepository> myRepositories;
        @Nonnull
        protected final String myBranchName;
        @Nonnull
        protected final GitRepository mySelectedRepository;
        @Nonnull
        protected final GitBrancher myBrancher;
        @Nonnull
        protected final GitBranchManager myGitBranchManager;

        protected AbstractBranchActions(
            @Nonnull Project project,
            @Nonnull List<GitRepository> repositories,
            @Nonnull String branchName,
            @Nonnull GitRepository selectedRepository
        ) {
            myProject = project;
            myRepositories = repositories;
            myBranchName = branchName;
            mySelectedRepository = selectedRepository;

            myBrancher = myProject.getInstance(GitBrancher.class);
            myGitBranchManager = project.getInstance(GitBranchManager.class);

            getTemplatePresentation().setDisabledMnemonic(true);
            getTemplatePresentation().setText(LocalizeValue.of(branchName));
        }

        protected class CompareAction extends DumbAwareAction {
            public CompareAction() {
                super(GitBranchesLocalize.actionCompareText());
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                myBrancher.compare(myBranchName, myRepositories, mySelectedRepository);
                reportUsage("git.branch.compare");
            }
        }

        protected class MergeAction extends DumbAwareAction {
            private final boolean myLocalBranch;

            public MergeAction(boolean localBranch) {
                super(GitBranchesLocalize.actionMergeText());
                myLocalBranch = localBranch;
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                myBrancher.merge(myBranchName, deleteOnMerge(), myRepositories);
                reportUsage("git.branch.merge");
            }

            private GitBrancher.DeleteOnMergeOption deleteOnMerge() {
                if (myLocalBranch && !myBranchName.equals("master")) {
                    return GitBrancher.DeleteOnMergeOption.PROPOSE;
                }
                return GitBrancher.DeleteOnMergeOption.NOTHING;
            }
        }

        protected class RebaseAction extends DumbAwareAction {
            public RebaseAction() {
                super(GitBranchesLocalize.actionRebaseOntoText());
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                myBrancher.rebase(myRepositories, myBranchName);
                reportUsage("git.branch.rebase");
            }
        }

        protected class CheckoutWithRebaseAction extends DumbAwareAction {
            public CheckoutWithRebaseAction() {
                super(
                    GitBranchesLocalize.actionCheckoutWithRebaseText(),
                    GitBranchesLocalize.actionCheckoutWithRebase0Description(myBranchName)
                );
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                myBrancher.rebaseOnCurrent(myRepositories, myBranchName);
                reportUsage("git.branch.checkout.with.rebase");
            }
        }
    }

    public static class LocalBranchActions extends AbstractBranchActions implements PopupElementWithAdditionalInfo {
        public LocalBranchActions(
            @Nonnull Project project,
            @Nonnull List<GitRepository> repositories,
            @Nonnull String branchName,
            @Nonnull GitRepository selectedRepository
        ) {
            super(project, repositories, branchName, selectedRepository);
            setFavorite(myGitBranchManager.isFavorite(LOCAL, repositories.size() > 1 ? null : mySelectedRepository, myBranchName));
        }

        @Nonnull
        List<GitRepository> getRepositories() {
            return myRepositories;
        }

        @Nonnull
        public String getBranchName() {
            return myBranchName;
        }

        @Nonnull
        @Override
        public AnAction[] getChildren(@Nullable AnActionEvent e) {
            return new AnAction[]{
                new CheckoutAction(),
                new CheckoutAsNewBranch(),
                new CompareAction(),
                new RebaseAction(),
                new CheckoutWithRebaseAction(),
                new MergeAction(true),
                new RenameBranchAction(),
                new DeleteAction()
            };
        }

        @Override
        @Nullable
        public String getInfoText() {
            return new GitMultiRootBranchConfig(myRepositories).getTrackedBranch(myBranchName);
        }

        @Override
        public void toggle() {
            super.toggle();
            myGitBranchManager.setFavorite(LOCAL, myRepositories.size() > 1 ? null : mySelectedRepository, myBranchName, isFavorite());
        }

        private class CheckoutAction extends DumbAwareAction {
            CheckoutAction() {
                super(GitBranchesLocalize.actionCheckoutText());
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                myBrancher.checkout(myBranchName, false, myRepositories, null);
                reportUsage("git.branch.checkout.local");
            }
        }

        private class CheckoutAsNewBranch extends DumbAwareAction {
            CheckoutAsNewBranch() {
                super(GitBranchesLocalize.actionCheckoutAsNewBranchText());
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                InputBoxBuilder.text()
                    .title(GitBranchesLocalize.dialogTitleCheckoutNewBranchFrom0(myBranchName))
                    .text(GitBranchesLocalize.dialogMessageNewBranchName())
                    .validator(GitNewBranchNameValidator.newInstance(myRepositories))
                    .showAsync(myProject)
                    .whenCompleteAsync((name, error) -> {
                        if (name != null) {
                            myBrancher.checkoutNewBranchStartingFrom(name, myBranchName, myRepositories, null);
                        }
                    }, UIAccess.current());
                reportUsage("git.checkout.as.new.branch");
            }
        }

        private class RenameBranchAction extends DumbAwareAction {
            public RenameBranchAction() {
                super(GitBranchesLocalize.actionRenameText());
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                InputBoxBuilder.text()
                    .title(GitBranchesLocalize.dialogTitleRenameBranch0(myBranchName))
                    .text(GitBranchesLocalize.dialogMessageNewNameForTheBranch0(myBranchName))
                    .value(myBranchName)
                    .validator(GitNewBranchNameValidator.newInstance(myRepositories))
                    .showAsync(myProject)
                    .whenCompleteAsync((newName, error) -> {
                        if (newName != null) {
                            myBrancher.renameBranch(myBranchName, newName, myRepositories);
                            reportUsage("git.branch.rename");
                        }
                    }, UIAccess.current());
            }
        }

        private class DeleteAction extends DumbAwareAction {
            DeleteAction() {
                super(GitBranchesLocalize.actionDeleteText());
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                myBrancher.deleteBranch(myBranchName, myRepositories);
                reportUsage("git.branch.delete.local");
            }
        }
    }

    /**
     * Actions available for remote branches
     */
    public static class RemoteBranchActions extends AbstractBranchActions {
        public RemoteBranchActions(
            @Nonnull Project project,
            @Nonnull List<GitRepository> repositories,
            @Nonnull String branchName,
            @Nonnull GitRepository selectedRepository
        ) {
            super(project, repositories, branchName, selectedRepository);
            setFavorite(myGitBranchManager.isFavorite(REMOTE, repositories.size() > 1 ? null : mySelectedRepository, myBranchName));
        }

        @Override
        public void toggle() {
            super.toggle();
            myGitBranchManager.setFavorite(REMOTE, myRepositories.size() > 1 ? null : mySelectedRepository, myBranchName, isFavorite());
        }

        @Nonnull
        @Override
        public AnAction[] getChildren(@Nullable AnActionEvent e) {
            return new AnAction[]{
                new CheckoutRemoteBranchAction(),
                new CompareAction(),
                new RebaseAction(),
                new MergeAction(false),
                new RemoteDeleteAction()
            };
        }

        private class CheckoutRemoteBranchAction extends DumbAwareAction {
            public CheckoutRemoteBranchAction() {
                super(GitBranchesLocalize.actionCheckoutAsNewLocalBranchText());
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                InputBoxBuilder.text()
                    .title(GitBranchesLocalize.dialogTitleCheckoutRemoteBranch())
                    .text(GitBranchesLocalize.dialogMessageNewBranchName())
                    .value(guessBranchName())
                    .validator(GitNewBranchNameValidator.newInstance(myRepositories))
                    .showAsync(myProject)
                    .whenCompleteAsync((name, error) -> {
                        if (name != null) {
                            myBrancher.checkoutNewBranchStartingFrom(name, myBranchName, myRepositories, null);
                            reportUsage("git.branch.checkout.remote");
                        }
                    }, UIAccess.current());
            }

            private String guessBranchName() {
                // TODO: check if we already have a branch with that name;
                // TODO: check if that branch tracks this remote branch. Show different messages
                int slashPosition = myBranchName.indexOf("/");
                // if no slash is found (for example, in the case of git-svn remote branches), propose the whole name.
                return myBranchName.substring(slashPosition + 1);
            }
        }

        private class RemoteDeleteAction extends DumbAwareAction {
            RemoteDeleteAction() {
                super(GitBranchesLocalize.actionDeleteText());
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                myBrancher.deleteRemoteBranch(myBranchName, myRepositories);
                reportUsage("git.branch.delete.remote");
            }
        }
    }
}
