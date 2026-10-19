package git4idea.ui.branch.popup;

import consulo.project.Project;
import consulo.ui.ex.action.ActionGroup;
import consulo.ui.ex.action.ActionManager;
import consulo.ui.ex.action.AnAction;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsBranchesTreeModel;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsBranchesTreePopupModel;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsBranchesTreePopupStepBase;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsRef;
import git4idea.repo.GitRepository;
import git4idea.ui.branch.GitBranchManager;
import git4idea.ui.branch.GitBranchPopupActions;
import git4idea.ui.branch.GitBranchPopupFetchAction;
import jakarta.annotation.Nullable;

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
    public DvcsBranchesTreeModel createRefTreeModel() {
        GitBranchesTreeSingleRepoModel model = new GitBranchesTreeSingleRepoModel(myProject, createModel(), List.of());
        model.init();
        return model;
    }

    @Nullable
    @Override
    public ActionGroup createRefActionGroup(DvcsRef ref) {
        return ref instanceof GitRefAdapter adapter
            ? GitBranchesTreePopupStep.createRefActionGroup(myProject, myRepository, adapter.reference())
            : null;
    }

    @Nullable
    @Override
    public AnAction createNewBranchAction() {
        return new GitBranchPopupActions.GitNewBranchAction(myProject, List.of(myRepository));
    }

    @Nullable
    @Override
    public AnAction createCheckoutAction(DvcsRef ref) {
        if (!(ref instanceof GitRefAdapter adapter) || ref.getName().equals(myRepository.getCurrentBranchName())) {
            return null;
        }
        ActionGroup group = GitBranchesTreePopupStep.createRefActionGroup(myProject, myRepository, adapter.reference());
        AnAction[] children = group == null ? AnAction.EMPTY_ARRAY : group.getChildren(null);
        return children.length == 0 ? null : children[0];
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
