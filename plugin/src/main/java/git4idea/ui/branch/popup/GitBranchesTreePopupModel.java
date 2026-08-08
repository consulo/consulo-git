package git4idea.ui.branch.popup;

import consulo.project.Project;
import consulo.ui.ex.action.ActionManager;
import consulo.ui.ex.action.AnAction;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsBranchesTreeModel;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsBranchesTreePopupModel;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsBranchesTreePopupStepBase;
import git4idea.repo.GitRepository;
import git4idea.ui.branch.GitBranchManager;
import git4idea.ui.branch.GitBranchPopupActions;
import git4idea.ui.branch.GitBranchPopupFetchAction;

import java.util.List;
import java.util.function.Consumer;

/**
 * @author VISTALL
 * @since 2026-08-03
 */
public class GitBranchesTreePopupModel extends DvcsBranchesTreePopupModel<GitRepository> {
    private final Project myProject;

    public GitBranchesTreePopupModel(ActionManager actionManager,
                                     GitRepository repository,
                                     Project project) {
        super(actionManager, repository);
        myProject = project;
    }

    @Override
    public DvcsBranchesTreeModel createTreeModel() {
        return new GitBranchesTreeSingleRepoModel(myProject, createModel(), collectActions());
    }

    private GitDvcsRepositoryModel createModel() {
        return new GitDvcsRepositoryModel(myRepository, myProject.getInstance(GitBranchManager.class));
    }

    @Override
    public DvcsBranchesTreePopupStepBase createTreePopupStep(DvcsBranchesTreeModel dvcsBranchesTreeModel) {
        return new GitBranchesTreePopupStep(myProject, createModel(), collectActions());
    }

    @Override
    public void appendHeaderActions(Consumer<AnAction> consumer) {
        super.appendHeaderActions(consumer);
        consumer.accept(new GitBranchPopupFetchAction());
    }

    @Override
    public void appendFirstPopupActions(Consumer<AnAction> consumer) {
        consumer.accept(new GitBranchPopupActions.GitNewBranchAction(myProject, List.of(myRepository)));
        consumer.accept(new GitBranchPopupActions.CheckoutRevisionActions(myProject, List.of(myRepository)));
    }
}
