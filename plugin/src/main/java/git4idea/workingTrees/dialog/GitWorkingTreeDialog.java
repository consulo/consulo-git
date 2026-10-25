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

import consulo.application.util.UserHomeFileUtil;
import consulo.disposer.Disposable;
import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.fileChooser.FileChooserTextBoxBuilder;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.navigation.OpenFileDescriptorFactory;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.CheckBox;
import consulo.ui.ComboBox;
import consulo.ui.Component;
import consulo.ui.InputProblem;
import consulo.ui.Label;
import consulo.ui.TextAttribute;
import consulo.ui.TextBox;
import consulo.ui.Toast;
import consulo.ui.ToastStyle;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.dialog.Dialog;
import consulo.ui.ex.dialog.DialogDescriptor;
import consulo.ui.ex.dialog.DialogService;
import consulo.ui.ex.dialog.DialogValue;
import consulo.ui.ex.dialog.action.DialogOkAction;
import consulo.ui.layout.VerticalLayout;
import consulo.ui.model.FlatDataModel;
import consulo.ui.model.MutableFlatDataModel;
import consulo.ui.style.ComponentColors;
import consulo.ui.util.FormBuilder;
import consulo.util.io.PathUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsBranchesTreeIconProvider;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitBranch;
import git4idea.GitLocalBranch;
import git4idea.GitReference;
import git4idea.GitRemoteBranch;
import git4idea.GitStandardLocalBranch;
import git4idea.GitTag;
import git4idea.GitUtil;
import git4idea.GitWorkingTree;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryFiles;
import git4idea.repo.GitSubmodule;
import git4idea.validators.GitBranchValidator;
import git4idea.validators.GitRefNameValidator;
import git4idea.workingTrees.GitWorktreeAdditionalConfigCopier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class GitWorkingTreeDialog extends DialogDescriptor {
    public static final String PROJECT_NAME_FIELD_NAME = "Git.Worktree.Dialog.ProjectNameField";

    private static final DialogValue CREATE_WORKTREE_INCLUDE_VALUE = new DialogValue() {
        @Override
        public String toString() {
            return "CREATE_WORKTREE_INCLUDE_VALUE";
        }
    };

    private final GitWorktreeDialogContext myData;
    private final List<GitRepository> myCandidateRepositories;
    private final GitRefNameValidator myValidator = GitRefNameValidator.getInstance();
    private final List<GitWorktreeSettingsPanelHandle> myAdditionalSettingsHandles = new ArrayList<>();
    private final GitWorktreeSetupScriptPanel mySetupScriptPanel;
    private final GitWorktreeConfigCategoriesPanel myConfigCategoriesPanel;

    private GitRepository mySelectedRepository;
    private @Nullable GitRefWithWorkingTree myExistingRef;
    private boolean myCreateNewBranch;
    private String myNewBranchName;
    private String myProjectName;
    private boolean myProjectNameModifiedByUser;
    private boolean myUpdatingProjectName;

    private @Nullable Dialog myDialog;
    private @Nullable VirtualFile myWorktreeIncludeFile;

    private @Nullable MutableFlatDataModel<GitRefWithWorkingTree> myRefModel;
    private @Nullable ComboBox<GitRefWithWorkingTree> myRefComboBox;
    private @Nullable Label myExistingRefComment;
    private @Nullable Toast mySubmoduleInfo;
    private @Nullable CheckBox myCreateNewBranchCheckBox;
    private @Nullable TextBox myNewBranchNameField;
    private @Nullable TextBox myProjectNameField;
    private FileChooserTextBoxBuilder.@Nullable Controller myParentPathController;
    private @Nullable Label myParentPathComment;
    private @Nullable Label myErrorLabel;

    public GitWorkingTreeDialog(GitWorktreeDialogContext data) {
        super(GitLocalize.workingTreeDialogTitle());
        myData = data;
        myCandidateRepositories = data.candidateRepositories();
        GitWorkingTreeDialogState savedState = data.initialState();
        mySelectedRepository = savedState != null ? savedState.repository() : data.initialRepository();
        GitReference initialRef = savedState != null ? savedState.existingRef() : data.initialExistingRef();
        myExistingRef = initialRef == null ? null : toRefWithWorkingTree(initialRef);
        myCreateNewBranch = savedState != null && savedState.createNewBranch();
        myNewBranchName = savedState != null ? savedState.newBranchName() : "";
        mySetupScriptPanel = new GitWorktreeSetupScriptPanel(savedState, data.project());
        myConfigCategoriesPanel = new GitWorktreeConfigCategoriesPanel(savedState, data.project(), this::getCurrentRepository, mySetupScriptPanel, file -> {
            myWorktreeIncludeFile = file;
            Dialog dialog = myDialog;
            if (dialog != null) {
                dialog.doOkAction(CREATE_WORKTREE_INCLUDE_VALUE);
            }
            OpenFileDescriptorFactory.getInstance(data.project()).newBuilder(file).build().navigate(true);
        });
        myProjectName = savedState != null ? savedState.projectName() : suggestProjectName();
        myProjectNameModifiedByUser = savedState != null && !savedState.projectName().equals(suggestProjectName());
    }

    @RequiredUIAccess
    public CompletableFuture<GitWorkingTreeDialogExit> showAsync() {
        Dialog dialog = myData.project().getApplication().getInstance(DialogService.class).build(this);
        myDialog = dialog;
        return dialog.showAsync().handle((value, error) -> {
            if (error != null || value == null) {
                return GitWorkingTreeDialogExit.CANCEL;
            }
            if (value == CREATE_WORKTREE_INCLUDE_VALUE) {
                return GitWorkingTreeDialogExit.CREATE_WORKTREE_INCLUDE;
            }
            return GitWorkingTreeDialogExit.OK;
        });
    }

    /**
     * The .worktreeinclude file the user just created or opened for editing, if any; set right before the dialog closes.
     */
    public @Nullable VirtualFile getWorktreeIncludeFile() {
        return myWorktreeIncludeFile;
    }

    private GitRepository getCurrentRepository() {
        return mySelectedRepository;
    }

    private static Map<GitStandardLocalBranch, GitWorkingTree> branchToWorkingTreeMap(GitRepository repository) {
        Map<GitStandardLocalBranch, GitWorkingTree> result = new HashMap<>();
        for (GitWorkingTree tree : repository.getWorkingTreeHolder().getWorkingTrees()) {
            GitStandardLocalBranch branch = tree.getCurrentBranch();
            if (branch != null) {
                result.put(branch, tree);
            }
        }
        return result;
    }

    private static List<String> getLocalBranchNames(GitRepository repository) {
        List<String> result = new ArrayList<>();
        for (GitLocalBranch branch : repository.getBranches().getLocalBranches()) {
            result.add(branch.getName());
        }
        return result;
    }

    private GitRefWithWorkingTree toRefWithWorkingTree(GitReference reference) {
        if (reference instanceof GitStandardLocalBranch localBranch) {
            return new GitRefWithWorkingTree(reference, branchToWorkingTreeMap(getCurrentRepository()).get(localBranch));
        }
        return new GitRefWithWorkingTree(reference, null);
    }

    @Override
    public @Nullable String getDimensionServiceKey() {
        return "Git.CreateWorkingTreeDialog";
    }

    @Override
    protected DialogOkAction createOkAction() {
        return new DialogOkAction(myData.openAfterCreation()
                                      ? GitLocalize.workingTreeDialogButtonOk()
                                      : GitLocalize.workingTreeDialogButtonOkCreateOnly());
    }

    @RequiredUIAccess
    @Override
    public Component createCenterComponent(Disposable uiDisposable) {
        VerticalLayout root = VerticalLayout.create();

        // Tell that a worktree of a submodule is standalone and does not replace the submodule in the parent repository
        // (the parent keeps pointing at the original checkout).
        Toast submoduleInfo = Toast.create().setText(GitLocalize.workingTreeDialogSubmoduleInfo());
        submoduleInfo.addStyle(ToastStyle.INFO);
        submoduleInfo.setVisible(GitSubmodule.isSubmodule(getCurrentRepository()));
        mySubmoduleInfo = submoduleInfo;
        root.add(submoduleInfo);

        FormBuilder form = FormBuilder.create();

        // Multi-root project: let the user pick which repository the worktree is created for.
        if (myCandidateRepositories.size() > 1) {
            ComboBox<GitRepository> repositoryComboBox = ComboBox.create(myCandidateRepositories);
            repositoryComboBox.setRenderer((presentation, item) -> {
                GitRepository repository = item.getValue();
                presentation.append(repository == null ? "" : UserHomeFileUtil.getLocationRelativeToUserHome(repository.getRoot().getPath()));
            });
            repositoryComboBox.setValue(getCurrentRepository());
            repositoryComboBox.addValueListener(event -> {
                GitRepository repository = event.getValue();
                if (repository != null && repository != mySelectedRepository) {
                    mySelectedRepository = repository;
                    onRepositoryChanged();
                }
            });
            form.addLabeled(GitLocalize.workingTreeDialogLabelRepository(), repositoryComboBox);
        }

        List<GitRefWithWorkingTree> refs = computeRefsWithWorkingTrees();
        MutableFlatDataModel<GitRefWithWorkingTree> refModel = FlatDataModel.of(refs);
        myRefModel = refModel;
        ComboBox<GitRefWithWorkingTree> refComboBox = ComboBox.create(refModel);
        refComboBox.setRenderer((presentation, item) -> {
            GitRefWithWorkingTree value = item.getValue();
            if (value == null) {
                presentation.append(GitLocalize.workingTreeDialogExistingBranchComboBoxEmptyText());
                return;
            }
            GitReference ref = value.ref();
            boolean isCurrent = ref instanceof GitBranch branch && branch.equals(getCurrentRepository().getCurrentBranch());
            presentation.withIcon(ref instanceof GitTag ? PlatformIconGroup.nodesTag() : DvcsBranchesTreeIconProvider.forRef(null, isCurrent, false, false, false));
            presentation.append(ref.getName());
            GitWorkingTree workingTree = value.workingTree();
            if (workingTree != null) {
                presentation.append(LocalizeValue.of("   " + workingTree.getPath().getName()), TextAttribute.GRAYED);
            }
        });
        myRefComboBox = refComboBox;
        // Preselect the current branch if no explicit ref was provided.
        if (myExistingRef == null) {
            myExistingRef = findCurrentOrFirst(refs);
        }
        else {
            GitRefWithWorkingTree initial = myExistingRef;
            myExistingRef = refs.stream().filter(it -> it.ref().equals(initial.ref())).findFirst().orElse(initial);
        }
        refComboBox.setValue(myExistingRef);
        if (!myProjectNameModifiedByUser) {
            myProjectName = suggestProjectName();
        }
        refComboBox.addValueListener(event -> {
            myExistingRef = event.getValue();
            updateExistingRefComment();
            refreshProjectNameSuggestion();
            clearError();
        });

        Label existingRefComment = Label.create();
        existingRefComment.setForegroundColor(ComponentColors.DISABLED_TEXT);
        myExistingRefComment = existingRefComment;

        VerticalLayout existingRefLayout = VerticalLayout.create();
        existingRefLayout.add(refComboBox);
        existingRefLayout.add(existingRefComment);
        form.addLabeled(GitLocalize.workingTreeDialogLabelExistingBranch(), existingRefLayout);

        CheckBox createNewBranchCheckBox = CheckBox.create(GitLocalize.workingTreeDialogCheckboxNewBranch());
        createNewBranchCheckBox.setValue(myCreateNewBranch);
        myCreateNewBranchCheckBox = createNewBranchCheckBox;
        TextBox newBranchNameField = TextBox.create(myNewBranchName);
        myNewBranchNameField = newBranchNameField;
        createNewBranchCheckBox.addValueListener(event -> {
            myCreateNewBranch = event.getValue();
            updateExistingRefComment();
            refreshProjectNameSuggestion();
            clearError();
        });
        newBranchNameField.addValueListener(event -> {
            String text = StringUtil.notNullize(event.getValue());
            String cleaned = myValidator.cleanUpBranchNameOnTyping(text);
            if (!cleaned.equals(text)) {
                newBranchNameField.setValue(cleaned);
                return;
            }
            myNewBranchName = cleaned;
            if (myCreateNewBranch != !cleaned.isEmpty()) {
                myCreateNewBranch = !cleaned.isEmpty();
                createNewBranchCheckBox.setValue(myCreateNewBranch);
            }
            updateExistingRefComment();
            refreshProjectNameSuggestion();
            clearError();
        });
        form.addLabeled(createNewBranchCheckBox, newBranchNameField);

        TextBox projectNameField = TextBox.create(myProjectName);
        projectNameField.addValueListener(event -> {
            myProjectName = StringUtil.notNullize(event.getValue());
            if (!myUpdatingProjectName) {
                myProjectNameModifiedByUser = true;
            }
            updateParentPathComment();
            clearError();
        });
        myProjectNameField = projectNameField;
        form.addLabeled(GitLocalize.workingTreeDialogLabelName(), projectNameField);

        FileChooserTextBoxBuilder parentPathBuilder = FileChooserTextBoxBuilder.create(myData.project());
        parentPathBuilder.fileChooserDescriptor(FileChooserDescriptorFactory.createSingleFolderDescriptor());
        parentPathBuilder.dialogTitle(GitLocalize.workingTreeDialogLabelLocationFileChooserTitle());
        FileChooserTextBoxBuilder.Controller parentPathController = parentPathBuilder.build();
        GitWorkingTreeDialogState savedState = myData.initialState();
        String initialParentPath = savedState != null ? savedState.parentPath() : StringUtil.notNullize(myData.initialParentPath());
        parentPathController.setValue(initialParentPath);
        parentPathController.getComponent().addValueListener(event -> {
            updateParentPathComment();
            clearError();
        });
        myParentPathController = parentPathController;

        Label parentPathComment = Label.create();
        parentPathComment.setForegroundColor(ComponentColors.DISABLED_TEXT);
        myParentPathComment = parentPathComment;

        VerticalLayout locationLayout = VerticalLayout.create();
        locationLayout.add(parentPathController.getComponent());
        locationLayout.add(parentPathComment);
        form.addLabeled(GitLocalize.workingTreeDialogLabelLocation(), locationLayout);

        root.add(form.build());

        myConfigCategoriesPanel.buildPanel(root);

        myData.project().getApplication().getExtensionPoint(GitWorktreeSettingsPanelProvider.class)
            .forEach(provider -> myAdditionalSettingsHandles.add(provider.createPanel(root, myData.project())));

        Label errorLabel = Label.create();
        errorLabel.setForegroundColor(ComponentColors.ERROR_FOREGROUND);
        errorLabel.setVisible(false);
        myErrorLabel = errorLabel;
        root.add(errorLabel);

        updateExistingRefComment();
        updateParentPathComment();
        return root;
    }

    private @Nullable GitRefWithWorkingTree findCurrentOrFirst(List<GitRefWithWorkingTree> refs) {
        GitLocalBranch currentBranch = getCurrentRepository().getCurrentBranch();
        for (GitRefWithWorkingTree ref : refs) {
            if (Objects.equals(ref.ref(), currentBranch)) {
                return ref;
            }
        }
        return refs.isEmpty() ? null : refs.getFirst();
    }

    /**
     * Rebuilds the branch list and re-suggests the project name when the target repository is switched.
     */
    @RequiredUIAccess
    private void onRepositoryChanged() {
        List<GitRefWithWorkingTree> refs = computeRefsWithWorkingTrees();
        MutableFlatDataModel<GitRefWithWorkingTree> refModel = myRefModel;
        if (refModel != null) {
            refModel.replaceAll(refs);
        }
        myExistingRef = findCurrentOrFirst(refs);
        ComboBox<GitRefWithWorkingTree> refComboBox = myRefComboBox;
        if (refComboBox != null) {
            refComboBox.setValue(myExistingRef);
        }
        Toast submoduleInfo = mySubmoduleInfo;
        if (submoduleInfo != null) {
            submoduleInfo.setVisible(GitSubmodule.isSubmodule(getCurrentRepository()));
        }
        myConfigCategoriesPanel.updateWorktreeIncludeButtons();
        refreshProjectNameSuggestion();
    }

    @RequiredUIAccess
    private void refreshProjectNameSuggestion() {
        if (myProjectNameModifiedByUser) {
            return;
        }
        TextBox projectNameField = myProjectNameField;
        if (projectNameField == null) {
            return;
        }
        myUpdatingProjectName = true;
        try {
            projectNameField.setValue(suggestProjectName());
        }
        finally {
            myUpdatingProjectName = false;
        }
    }

    @RequiredUIAccess
    private void updateExistingRefComment() {
        Label comment = myExistingRefComment;
        if (comment == null) {
            return;
        }
        GitRefWithWorkingTree value = myExistingRef;
        boolean visible = value != null && value.workingTree() != null && !myCreateNewBranch;
        comment.setText(visible ? GitLocalize.workingTreeDialogBranchValidationAlreadyCheckedOutInWorkingTree() : LocalizeValue.empty());
        comment.setVisible(visible);
    }

    @RequiredUIAccess
    private void updateParentPathComment() {
        Label comment = myParentPathComment;
        if (comment == null) {
            return;
        }
        String parent = getParentPath();
        String child = myProjectName;
        LocalizeValue text;
        if (parent.isBlank()) {
            text = LocalizeValue.empty();
        }
        else if (child.isBlank()) {
            text = GitLocalize.workingTreeDialogLabelLocationComment(UserHomeFileUtil.getLocationRelativeToUserHome(parent));
        }
        else {
            text = GitLocalize.workingTreeDialogLabelLocationComment(UserHomeFileUtil.getLocationRelativeToUserHome(parent + "/" + child));
        }
        comment.setText(text);
    }

    private String getParentPath() {
        FileChooserTextBoxBuilder.Controller controller = myParentPathController;
        return controller == null ? "" : controller.getValue();
    }

    @RequiredUIAccess
    private void clearError() {
        Label errorLabel = myErrorLabel;
        if (errorLabel != null && errorLabel.isVisible()) {
            errorLabel.setVisible(false);
        }
    }

    @RequiredUIAccess
    private void showError(LocalizeValue message) {
        Label errorLabel = myErrorLabel;
        if (errorLabel != null) {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
        }
    }

    @RequiredUIAccess
    @Override
    public boolean canHandle(AnAction action, @Nullable DialogValue value) {
        if (!(action instanceof DialogOkAction) || value == CREATE_WORKTREE_INCLUDE_VALUE) {
            return true;
        }
        LocalizeValue error = validateExistingRefOnApply();
        if (error.isEmpty()) {
            error = validateNewBranchNameField();
        }
        if (error.isEmpty()) {
            error = validateProjectName();
        }
        if (error.isEmpty()) {
            error = validateWorktreePath(getParentPath().trim(), myProjectName.trim());
        }
        if (error.isEmpty()) {
            error = mySetupScriptPanel.validateSetupScriptPath();
        }
        if (error.isNotEmpty()) {
            showError(error);
            return false;
        }
        return true;
    }

    private LocalizeValue validateExistingRefOnApply() {
        GitRefWithWorkingTree value = myExistingRef;
        if (value == null) {
            return GitLocalize.workingTreeDialogLocationValidationSelectBranch();
        }
        GitReference ref = value.ref();
        if (ref instanceof GitRemoteBranch remoteBranch && !myCreateNewBranch) {
            String defaultLocalBranchName = remoteBranch.getNameForRemoteOperations();
            // can have remote conflict if git-svn is used - suggested local name will be equal to selected remote,
            // see git4idea.ui.branch.GitRemoteBranchCheckoutUtil.checkoutRemoteBranch
            if (GitReference.BRANCH_NAME_HASHING_STRATEGY.equals(defaultLocalBranchName, ref.getName())) {
                return GitLocalize.workingTreeDialogBranchValidationProvideExplicitLocalBranchName();
            }
            if (getLocalBranchNames(getCurrentRepository()).contains(defaultLocalBranchName)) {
                return GitLocalize.workingTreeDialogBranchValidationDefaultExists(ref.getName());
            }
        }
        return LocalizeValue.empty();
    }

    private LocalizeValue validateNewBranchNameField() {
        if (!myCreateNewBranch) {
            return LocalizeValue.empty();
        }
        String name = myNewBranchName;
        InputProblem problem = GitBranchValidator.checkRefName(name);
        if (problem != null) {
            return problem.message();
        }
        if (getLocalBranchNames(getCurrentRepository()).contains(name)) {
            return GitLocalize.workingTreeDialogBranchValidationAlreadyExists(name);
        }
        return LocalizeValue.empty();
    }

    private LocalizeValue validateProjectName() {
        if (!PathUtil.isValidFileName(myProjectName.trim())) {
            return GitLocalize.workingTreeDialogNameValidationInvalidFormat();
        }
        return LocalizeValue.empty();
    }

    private List<GitRefWithWorkingTree> computeRefsWithWorkingTrees() {
        GitRepository repository = getCurrentRepository();
        List<GitRefWithWorkingTree> result = new ArrayList<>();
        repository.getBranches().getLocalBranches().stream()
            .sorted(Comparator.comparing(GitReference::getName))
            .forEach(branch -> result.add(toRefWithWorkingTree(branch)));
        if (result.isEmpty()) {
            GitLocalBranch currentBranch = repository.getCurrentBranch();
            if (currentBranch != null) {
                result.add(toRefWithWorkingTree(currentBranch));
            }
        }
        repository.getBranches().getRemoteBranches().stream()
            .sorted(Comparator.comparing(GitReference::getName))
            .forEach(branch -> result.add(new GitRefWithWorkingTree(branch, null)));

        repository.getTagsHolder().getTags().stream()
            .sorted(Comparator.comparing(GitReference::getName))
            .forEach(tag -> result.add(new GitRefWithWorkingTree(tag, null)));

        return result;
    }

    private String suggestProjectName() {
        String branchNameToCreate = myNewBranchName;
        GitRefWithWorkingTree existingRef = myExistingRef;
        String existingRefName = existingRef == null ? null : existingRef.ref().getName();
        String refToUse = myCreateNewBranch && !branchNameToCreate.isEmpty() ? branchNameToCreate : existingRefName;

        if (StringUtil.isEmpty(refToUse)) {
            return "";
        }
        return resolveProjectNameBase(getCurrentRepository()).getFileName() + "-" + refToUse.substring(refToUse.lastIndexOf('/') + 1);
    }

    /**
     * Snapshots the current field values, so a freshly re-created dialog can start from the same state.
     */
    public GitWorkingTreeDialogState captureState() {
        GitRefWithWorkingTree existingRef = myExistingRef;
        Map<GitWorktreeAdditionalConfigCopier, Boolean> additionalCopiers = myConfigCategoriesPanel.getAdditionalCopierSelections();
        return new GitWorkingTreeDialogState(
            getCurrentRepository(),
            existingRef == null ? null : existingRef.ref(),
            myCreateNewBranch,
            myNewBranchName,
            additionalCopiers,
            myConfigCategoriesPanel.isCopyWorktreeIncludeMatches(),
            mySetupScriptPanel.isRunSetupScript(),
            mySetupScriptPanel.getSetupScriptPath(),
            myProjectName,
            getParentPath(),
            myConfigCategoriesPanel.isExpanded()
        );
    }

    public GitWorktreeCreationRequest getWorkTreeData() {
        Path path = Paths.get(getParentPath()).resolve(myProjectName);
        GitRefWithWorkingTree existingRef = Objects.requireNonNull(myExistingRef);
        GitReference ref = existingRef.ref();
        WorktreeBranchSpec branch = myCreateNewBranch
            ? new CreateNewBranchWorktreeBranchSpec(ref, myNewBranchName)
            : new CheckoutExistingWorktreeBranchSpec(ref);
        Set<GitWorktreeAdditionalConfigCopier> enabledCopiers = new HashSet<>();
        myConfigCategoriesPanel.getAdditionalCopierSelections().forEach((copier, enabled) -> {
            if (enabled) {
                enabledCopiers.add(copier);
            }
        });
        return new GitWorktreeCreationRequest(
            getCurrentRepository(),
            VcsUtil.getFilePath(path.toFile(), true),
            branch,
            mySetupScriptPanel.getValidatedScriptPath(),
            myConfigCategoriesPanel.isCopyWorktreeIncludeMatches(),
            List.copyOf(myAdditionalSettingsHandles),
            enabledCopiers
        );
    }

    //should be the main repo root in case of working in a worktree, or the submodule root in case of a submodule
    static Path resolveProjectNameBase(GitRepository repository) {
        GitRepositoryFiles files = repository.getRepositoryFiles();
        Path worktreeGitDir = files.getWorktreeGitDir().toNioPath();
        Path commonGitDir = files.getCommonGitDir().toNioPath();
        if (worktreeGitDir.equals(commonGitDir)) {
            // Not a linked working tree: a plain repository, a bare repository, or a submodule's own working tree.
            // The repository's own root is the right name in every one of these cases.
            return repository.getRoot().toNioPath();
        }
        // A linked working tree of another repository. For a plain repository or a bare repository, the common
        // git dir sits right inside the main root, so the parent is the main root. For a submodule, it is
        // "<parent>/.git/modules/<submodule>" instead, and the submodule's own name is the common git dir's own
        // name (see GitRepositoryFiles.getCommonGitDir).
        return isSubmoduleModulesDir(commonGitDir) ? commonGitDir : commonGitDir.getParent();
    }

    // Detects the "<parent>/.git/modules/<submodule>" shape from GitRepositoryFiles.getCommonGitDir.
    // A plain or a bare repository never puts its common git dir under a "modules" directory of a ".git" directory,
    // so this check tells a submodule's common git dir apart from a repository whose own git dir has a custom name.
    private static boolean isSubmoduleModulesDir(Path commonGitDir) {
        Path dir = commonGitDir.getParent();
        while (dir != null) {
            Path parent = dir.getParent();
            if (dir.getFileName() != null && dir.getFileName().toString().equals("modules")
                && parent != null && parent.getFileName() != null && parent.getFileName().toString().equals(GitUtil.DOT_GIT)) {
                return true;
            }
            dir = parent;
        }
        return false;
    }

    static LocalizeValue validateWorktreeParentPath(String parentPath) {
        if (parentPath.isBlank()) {
            return GitLocalize.workingTreeDialogLocationValidationEmpty();
        }

        try {
            Paths.get(parentPath);
        }
        catch (InvalidPathException e) {
            return GitLocalize.workingTreeDialogLocationValidationInvalidPath();
        }
        return LocalizeValue.empty();
    }

    static LocalizeValue validateWorktreePath(String parentPath, String dirName) {
        LocalizeValue parentError = validateWorktreeParentPath(parentPath);
        if (parentError.isNotEmpty()) {
            return parentError;
        }
        Path parent = Paths.get(parentPath);

        if (!dirName.isBlank()) {
            Path fullPath;
            try {
                fullPath = parent.resolve(dirName);
            }
            catch (InvalidPathException e) {
                return GitLocalize.workingTreeDialogLocationValidationInvalidPath();
            }

            // Check the full target path first — if it already exists, we can skip the parent check entirely.
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(fullPath)) {
                return entries.iterator().hasNext()
                    ? GitLocalize.workingTreeDialogLocationValidationIsNotEmpty(UserHomeFileUtil.getLocationRelativeToUserHome(fullPath.toString()))
                    : LocalizeValue.empty();
            }
            catch (NoSuchFileException e) {
                // target doesn't exist — fall through to parent validation
            }
            catch (NotDirectoryException e) {
                return GitLocalize.workingTreeDialogLocationValidationIsAFile(UserHomeFileUtil.getLocationRelativeToUserHome(fullPath.toString()));
            }
            catch (IOException e) {
                return GitLocalize.workingTreeDialogLocationValidationInvalidPath();
            }
        }

        try (DirectoryStream<Path> ignored = Files.newDirectoryStream(parent)) {
            if (!Files.isWritable(parent)) {
                return GitLocalize.workingTreeDialogLocationValidationNotWritable(UserHomeFileUtil.getLocationRelativeToUserHome(parentPath));
            }
            return LocalizeValue.empty();
        }
        catch (NoSuchFileException e) {
            return LocalizeValue.empty();
        }
        catch (NotDirectoryException e) {
            return GitLocalize.workingTreeDialogLocationValidationNotADirectory(UserHomeFileUtil.getLocationRelativeToUserHome(parentPath));
        }
        catch (IOException e) {
            return GitLocalize.workingTreeDialogLocationValidationInvalidPath();
        }
    }
}
