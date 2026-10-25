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

import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.Button;
import consulo.ui.CheckBox;
import consulo.ui.Label;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.layout.FoldoutLayout;
import consulo.ui.layout.HorizontalLayout;
import consulo.ui.layout.VerticalLayout;
import consulo.ui.style.ComponentColors;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.repo.GitRepository;
import git4idea.workingTrees.GitWorktreeAdditionalConfigCopier;
import git4idea.workingTrees.GitWorktreeIncludeFileService;
import git4idea.workingTrees.GitWorktreeNotifications;
import git4idea.workingTrees.GitWorktreeProjectConfigService;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The "Copy project config" section of the New Worktree dialog: the additional-copier checkboxes, plus the
 * .worktreeinclude create/edit controls.
 * <p>
 * Call {@link #buildPanel} once to add its rows, and {@link #updateWorktreeIncludeButtons} whenever the dialog's selected
 * repository changes.
 */
public final class GitWorktreeConfigCategoriesPanel {
    private static final Logger LOG = Logger.getInstance(GitWorktreeConfigCategoriesPanel.class);

    private final Project myProject;
    private final Supplier<GitRepository> myGetCurrentRepository;
    private final GitWorktreeSetupScriptPanel mySetupScriptPanel;
    private final Consumer<VirtualFile> myOnEditWorktreeIncludeFile;
    private final GitWorktreeIncludeFileService myIncludeFileService;
    private final GitWorktreeProjectConfigService myProjectConfigService;

    private final Map<GitWorktreeAdditionalConfigCopier, Boolean> myInitialCopierSelections = new LinkedHashMap<>();
    private final Map<GitWorktreeAdditionalConfigCopier, CheckBox> myAdditionalCopierSelections = new LinkedHashMap<>();
    private final boolean myInitialCopyWorktreeIncludeMatches;
    private boolean myExpanded;
    private @Nullable Boolean myPreviousWorktreeIncludeExists;

    private @Nullable FoldoutLayout myFoldout;
    private @Nullable CheckBox myWorktreeIncludeCheckBox;
    private @Nullable Button myWorktreeIncludeCreateButton;
    private @Nullable Button myWorktreeIncludeAddIdeaSettingsButton;
    private @Nullable Button myWorktreeIncludeEditButton;

    public GitWorktreeConfigCategoriesPanel(@Nullable GitWorkingTreeDialogState savedState,
                                            Project project,
                                            Supplier<GitRepository> getCurrentRepository,
                                            GitWorktreeSetupScriptPanel setupScriptPanel,
                                            Consumer<VirtualFile> onEditWorktreeIncludeFile) {
        myProject = project;
        myGetCurrentRepository = getCurrentRepository;
        mySetupScriptPanel = setupScriptPanel;
        myOnEditWorktreeIncludeFile = onEditWorktreeIncludeFile;
        myIncludeFileService = GitWorktreeIncludeFileService.getInstance(project);
        myProjectConfigService = GitWorktreeProjectConfigService.getInstance(project);
        myExpanded = savedState != null && savedState.configCategoriesExpanded();
        myInitialCopyWorktreeIncludeMatches = savedState == null || savedState.copyWorktreeIncludeMatches();
        for (GitWorktreeAdditionalConfigCopier extension : GitWorktreeAdditionalConfigCopier.getExtensions()) {
            Boolean saved = savedState == null ? null : savedState.additionalCopiers().get(extension);
            myInitialCopierSelections.put(extension, saved == null || saved);
        }
    }

    public boolean isExpanded() {
        return myExpanded;
    }

    public Map<GitWorktreeAdditionalConfigCopier, Boolean> getAdditionalCopierSelections() {
        Map<GitWorktreeAdditionalConfigCopier, Boolean> result = new LinkedHashMap<>(myInitialCopierSelections);
        myAdditionalCopierSelections.forEach((extension, checkBox) -> result.put(extension, checkBox.getValueOrError()));
        return result;
    }

    public boolean isCopyWorktreeIncludeMatches() {
        CheckBox checkBox = myWorktreeIncludeCheckBox;
        return checkBox != null ? checkBox.getValueOrError() : myInitialCopyWorktreeIncludeMatches;
    }

    @RequiredUIAccess
    public void buildPanel(VerticalLayout panel) {
        VerticalLayout content = VerticalLayout.create();

        for (Map.Entry<GitWorktreeAdditionalConfigCopier, Boolean> entry : myInitialCopierSelections.entrySet()) {
            CheckBox checkBox = CheckBox.create(entry.getKey().getCheckboxText());
            checkBox.setValue(entry.getValue());
            checkBox.addValueListener(event -> updateSummary());
            myAdditionalCopierSelections.put(entry.getKey(), checkBox);
            content.add(checkBox);
        }

        CheckBox worktreeIncludeCheckBox = CheckBox.create(GitLocalize.workingTreeDialogCheckboxWorktreeInclude());
        worktreeIncludeCheckBox.setValue(myInitialCopyWorktreeIncludeMatches);
        worktreeIncludeCheckBox.addValueListener(event -> updateSummary());
        myWorktreeIncludeCheckBox = worktreeIncludeCheckBox;

        Button createButton = Button.create(GitLocalize.workingTreeDialogButtonWorktreeIncludeCreate(), event -> createWorktreeInclude());
        Button addIdeaSettingsButton = Button.create(GitLocalize.workingTreeDialogButtonWorktreeIncludeAddIdeaSettings(), event -> {
            GitRepository repository = myGetCurrentRepository.get();
            if (myIncludeFileService.addIdeaSettingsSection(repository)) {
                openWorktreeIncludeFileAndClose();
            }
            else {
                GitWorktreeNotifications.notifyWorktreeIncludeFileWriteFailed(myProject, repository.getRoot());
            }
        });
        Button editButton = Button.create(GitLocalize.workingTreeDialogButtonWorktreeIncludeEdit(), event -> openWorktreeIncludeFileAndClose());
        myWorktreeIncludeCreateButton = createButton;
        myWorktreeIncludeAddIdeaSettingsButton = addIdeaSettingsButton;
        myWorktreeIncludeEditButton = editButton;

        HorizontalLayout worktreeIncludeRow = HorizontalLayout.create();
        worktreeIncludeRow.add(worktreeIncludeCheckBox);
        worktreeIncludeRow.add(createButton);
        worktreeIncludeRow.add(addIdeaSettingsButton);
        worktreeIncludeRow.add(editButton);
        content.add(worktreeIncludeRow);

        Label worktreeIncludeComment = Label.create(GitLocalize.workingTreeDialogWorktreeIncludeComment());
        worktreeIncludeComment.setForegroundColor(ComponentColors.DISABLED_TEXT);
        content.add(worktreeIncludeComment);

        mySetupScriptPanel.buildPanel(content);
        mySetupScriptPanel.addChangeListener(this::updateSummary);

        FoldoutLayout foldout = FoldoutLayout.create(GitLocalize.workingTreeDialogGroupCopyProjectConfig(), content, myExpanded);
        foldout.addOpenedListener(event -> myExpanded = event.isOpened());
        myFoldout = foldout;
        panel.add(foldout);

        updateSummary();
        updateWorktreeIncludeButtons();
    }

    @RequiredUIAccess
    private void createWorktreeInclude() {
        GitRepository repository = myGetCurrentRepository.get();
        UIAccess uiAccess = UIAccess.current();
        CompletableFuture.supplyAsync(() -> !myProjectConfigService.isIdeaConfigCommittedToGit(repository.getRoot()), AppExecutorUtil.getAppExecutorService())
            .thenAcceptAsync(includeIdeaSettings -> {
                String content = GitWorktreeIncludeFileService.generateWorktreeIncludeContent(includeIdeaSettings);
                if (myIncludeFileService.writeWorktreeIncludeFile(repository, content)) {
                    openWorktreeIncludeFileAndClose();
                }
                else {
                    GitWorktreeNotifications.notifyWorktreeIncludeFileWriteFailed(myProject, repository.getRoot());
                }
            }, uiAccess);
    }

    @RequiredUIAccess
    private void updateSummary() {
        FoldoutLayout foldout = myFoldout;
        if (foldout == null) {
            return;
        }
        int enabledCount = (int) myAdditionalCopierSelections.values().stream().filter(CheckBox::getValueOrError).count()
            + (isCopyWorktreeIncludeMatches() ? 1 : 0)
            + (mySetupScriptPanel.isRunSetupScript() ? 1 : 0);
        int totalCount = myAdditionalCopierSelections.size() + 2;
        foldout.setTitle(LocalizeValue.join(
            GitLocalize.workingTreeDialogGroupCopyProjectConfig(),
            LocalizeValue.of(" ("),
            GitLocalize.workingTreeDialogCategorySummary(enabledCount, totalCount),
            LocalizeValue.of(")")
        ));
    }

    /**
     * Shows the Create button when the repository has no .worktreeinclude yet. Otherwise shows Edit, plus
     * Add idea settings when the file exists, is missing the idea-settings block, and idea settings are not
     * already committed to git.
     */
    @RequiredUIAccess
    public void updateWorktreeIncludeButtons() {
        GitRepository repository = myGetCurrentRepository.get();
        VirtualFile worktreeIncludeFile = repository.getRoot().findChild(GitWorktreeIncludeFileService.WORKTREE_INCLUDE_FILE_NAME);
        boolean worktreeIncludeExists = worktreeIncludeFile != null;
        CheckBox checkBox = myWorktreeIncludeCheckBox;
        if (checkBox == null) {
            return;
        }
        checkBox.setEnabled(worktreeIncludeExists);
        requireNonNull(myWorktreeIncludeCreateButton).setVisible(!worktreeIncludeExists);
        requireNonNull(myWorktreeIncludeEditButton).setVisible(worktreeIncludeExists);
        if (!worktreeIncludeExists || Boolean.FALSE.equals(myPreviousWorktreeIncludeExists)) {
            checkBox.setValue(worktreeIncludeExists);
        }
        myPreviousWorktreeIncludeExists = worktreeIncludeExists;

        Button addIdeaSettingsButton = requireNonNull(myWorktreeIncludeAddIdeaSettingsButton);
        addIdeaSettingsButton.setVisible(false);
        if (worktreeIncludeFile == null || hasIdeaSettingsSectionSafely(worktreeIncludeFile)) {
            return;
        }
        UIAccess uiAccess = UIAccess.current();
        CompletableFuture.supplyAsync(() -> !myProjectConfigService.isIdeaConfigCommittedToGit(repository.getRoot()), AppExecutorUtil.getAppExecutorService())
            .thenAcceptAsync(canAdd -> {
                if (myGetCurrentRepository.get() == repository) {
                    addIdeaSettingsButton.setVisible(canAdd);
                }
            }, uiAccess);
    }

    private static <T> T requireNonNull(@Nullable T value) {
        if (value == null) {
            throw new IllegalStateException("panel is not built");
        }
        return value;
    }

    /**
     * Hands the repository's existing .worktreeinclude file to {@code onEditWorktreeIncludeFile}.
     */
    private void openWorktreeIncludeFileAndClose() {
        VirtualFile file = myGetCurrentRepository.get().getRoot().findChild(GitWorktreeIncludeFileService.WORKTREE_INCLUDE_FILE_NAME);
        if (file != null) {
            myOnEditWorktreeIncludeFile.accept(file);
        }
    }

    /**
     * Returns {@code true} when {@code file} already has the idea-settings block, or {@code false} when {@code file} fails to read.
     */
    private static boolean hasIdeaSettingsSectionSafely(VirtualFile file) {
        String content;
        try {
            content = VirtualFileUtil.loadText(file);
        }
        catch (IOException e) {
            LOG.warn("Failed to read " + file + " while checking for the idea-settings section", e);
            return false;
        }
        return GitWorktreeIncludeFileService.hasIdeaSettingsSection(content);
    }
}
