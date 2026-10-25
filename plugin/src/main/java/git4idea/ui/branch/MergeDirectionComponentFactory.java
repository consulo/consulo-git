// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
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
package git4idea.ui.branch;

import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.ui.Button;
import consulo.ui.ComboBox;
import consulo.ui.Component;
import consulo.ui.Hyperlink;
import consulo.ui.ImageBox;
import consulo.ui.Label;
import consulo.ui.PopupPosition;
import consulo.ui.TextBox;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.popup.JBPopup;
import consulo.ui.ex.popup.JBPopupFactory;
import consulo.ui.layout.DockLayout;
import consulo.ui.layout.HorizontalLayout;
import consulo.ui.layout.VerticalLayout;
import consulo.ui.model.FlatDataModel;
import consulo.ui.model.MutableFlatDataModel;
import consulo.ui.style.ComponentColors;
import consulo.ui.util.LabeledBuilder;
import consulo.versionControlSystem.log.Hash;
import git4idea.GitBranch;
import git4idea.GitLocalBranch;
import git4idea.GitPushUtil;
import git4idea.GitRemoteBranch;
import git4idea.push.GitPushTarget;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public class MergeDirectionComponentFactory<RepoMapping extends GitRepositoryMappingData> {
    private final MergeDirectionModel<RepoMapping> myModel;
    private final Function<MergeDirectionModel<RepoMapping>, @Nullable LocalizeValue> myGetBaseRepoPresentation;
    private final Function<MergeDirectionModel<RepoMapping>, @Nullable LocalizeValue> myGetHeadRepoPresentation;

    public MergeDirectionComponentFactory(MergeDirectionModel<RepoMapping> model) {
        this(model,
            it -> LocalizeValue.of(it.getBaseRepo().toString()),
            it -> it.getHeadRepo() == null ? null : LocalizeValue.of(it.getHeadRepo().toString()));
    }

    public MergeDirectionComponentFactory(MergeDirectionModel<RepoMapping> model,
                                          Function<MergeDirectionModel<RepoMapping>, @Nullable LocalizeValue> getBaseRepoPresentation,
                                          Function<MergeDirectionModel<RepoMapping>, @Nullable LocalizeValue> getHeadRepoPresentation) {
        myModel = model;
        myGetBaseRepoPresentation = getBaseRepoPresentation;
        myGetHeadRepoPresentation = getHeadRepoPresentation;
    }

    @RequiredUIAccess
    public Component create() {
        Hyperlink base = Hyperlink.create(LocalizeValue.empty());
        base.addHyperlinkListener(event -> chooseBaseBranch(base, myModel.getBaseBranch(), myModel.getBaseRepo(), myModel::setBaseBranch));

        Hyperlink head = Hyperlink.create(LocalizeValue.empty());
        head.addHyperlinkListener(event -> {
            RepoMapping currentRepo = myModel.getHeadRepo();
            chooseHeadRepoAndBranch(head, currentRepo, myModel.getHeadBranch(), (repo, branch) -> {
                myModel.setHead(repo, branch);
                myModel.setHeadSetByUser(true);
            }, myModel.getKnownRepoMappings());
        });

        ImageBox changesWarningLabel = ImageBox.create(PlatformIconGroup.generalWarning());

        myModel.addAndInvokeDirectionChangesListener(() -> {
            RepoMapping headRepo = myModel.getHeadRepo();
            GitBranch headBranch = myModel.getHeadBranch();

            LocalizeValue baseText = Objects.requireNonNullElse(myGetBaseRepoPresentation.apply(myModel), GitLocalize.branchDirectionPanelSelectLink());
            base.setText(baseText);
            base.setToolTipText(baseText);
            LocalizeValue headText = Objects.requireNonNullElse(myGetHeadRepoPresentation.apply(myModel), GitLocalize.branchDirectionPanelSelectLink());
            head.setText(headText);
            head.setToolTipText(headText);

            updateChangesWarning(changesWarningLabel, headRepo, headBranch);
        });

        Label arrow = Label.create(LocalizeValue.of(" ← "));
        arrow.setForegroundColor(ComponentColors.DISABLED_TEXT);

        HorizontalLayout panel = HorizontalLayout.create();
        panel.add(base);
        panel.add(arrow);
        panel.add(head);
        panel.add(changesWarningLabel);
        return panel;
    }

    @RequiredUIAccess
    private void updateChangesWarning(ImageBox changesWarningLabel, @Nullable RepoMapping headRepo, @Nullable GitBranch headBranch) {
        if (headRepo != null && headBranch instanceof GitLocalBranch localHeadBranch) {
            GitPushTarget pushTarget = GitPushUtil.findPushTarget(headRepo.getGitRepository(), headRepo.getGitRemote(), localHeadBranch);
            if (pushTarget == null) {
                changesWarningLabel.setToolTipText(
                    GitLocalize.branchDirectionPanelWarningPush(localHeadBranch.getName(), headRepo.getGitRemote().getName()));
                changesWarningLabel.setVisible(true);
                return;
            }
            else {
                GitRepository repo = headRepo.getGitRepository();
                Hash localHash = repo.getBranches().getHash(localHeadBranch);
                Hash remoteHash = repo.getBranches().getHash(pushTarget.getBranch());
                if (!Objects.equals(localHash, remoteHash)) {
                    changesWarningLabel.setToolTipText(
                        GitLocalize.branchDirectionPanelWarningNotSynced(localHeadBranch.getName(), pushTarget.getBranch().getName()));
                    changesWarningLabel.setVisible(true);
                    return;
                }
            }
        }
        changesWarningLabel.setVisible(false);
    }

    private static <RepoMapping extends GitRepositoryMappingData> List<GitRemoteBranch> createRemoteBranchList(RepoMapping repoMapping) {
        GitRemote remote = repoMapping.getGitRemote();
        List<GitRemoteBranch> branches = new ArrayList<>();
        for (GitRemoteBranch branch : repoMapping.getGitRepository().getBranches().getRemoteBranches()) {
            if (branch.getRemote().equals(remote)) {
                branches.add(branch);
            }
        }
        branches.sort(null);
        return branches;
    }

    @RequiredUIAccess
    public static <RepoMapping extends GitRepositoryMappingData> void chooseBaseBranch(Component parentComponent,
                                                                                       @Nullable GitRemoteBranch currentBranch,
                                                                                       RepoMapping repoMapping,
                                                                                       Consumer<@Nullable GitRemoteBranch> applySelection) {
        List<GitRemoteBranch> branches = createRemoteBranchList(repoMapping);
        MutableFlatDataModel<GitRemoteBranch> branchModel = FlatDataModel.of(branches);
        ComboBox<GitRemoteBranch> branchComponent = createBranchComboBox(branchModel);
        if (currentBranch != null && branches.contains(currentBranch)) {
            branchComponent.setValue(currentBranch);
        }

        TextBox repoComponent = TextBox.create(repoMapping.getRepositoryPath());
        repoComponent.setEnabled(false);

        createBranchPopup(GitLocalize.branchDirectionPanelBaseRepoLabel(), repoComponent, branchComponent, false,
            () -> applySelection.accept(branchComponent.getValue()))
            .showBy(parentComponent, null, PopupPosition.BOTTOM);
    }

    @RequiredUIAccess
    private static <RepoMapping extends GitRepositoryMappingData> void chooseHeadRepoAndBranch(Component parentComponent,
                                                                                               @Nullable RepoMapping currentRepo,
                                                                                               @Nullable GitBranch currentBranch,
                                                                                               BiConsumer<@Nullable RepoMapping, @Nullable GitBranch> applySelection,
                                                                                               List<RepoMapping> items) {
        ComboBox<RepoMapping> repoComponent = ComboBox.create(FlatDataModel.of(items));
        repoComponent.setTextRenderer(it -> it == null ? LocalizeValue.empty() : LocalizeValue.of(it.getRepositoryPath()));
        repoComponent.setValue(currentRepo);

        MutableFlatDataModel<GitBranch> branchModel = FlatDataModel.of(List.of());
        ComboBox<GitBranch> branchComponent = createBranchComboBox(branchModel);

        updateHeadBranches(branchModel, branchComponent, repoComponent.getValue());
        repoComponent.addValueListener(event -> updateHeadBranches(branchModel, branchComponent, event.getValue()));

        if (currentBranch != null && branchModel.indexOf(currentBranch) >= 0) {
            branchComponent.setValue(currentBranch);
        }

        createBranchPopup(GitLocalize.branchDirectionPanelHeadRepoLabel(), repoComponent, branchComponent, true,
            () -> applySelection.accept(repoComponent.getValue(), branchComponent.getValue()))
            .showBy(parentComponent, null, PopupPosition.BOTTOM);
    }

    @RequiredUIAccess
    private static <RepoMapping extends GitRepositoryMappingData> void updateHeadBranches(MutableFlatDataModel<GitBranch> branchModel,
                                                                                          ComboBox<GitBranch> branchComponent,
                                                                                          @Nullable RepoMapping repoMapping) {
        GitRepository repo = repoMapping == null ? null : repoMapping.getGitRepository();
        if (repo == null) {
            branchModel.replaceAll(List.of());
            return;
        }

        GitRemote remote = repoMapping.getGitRemote();
        List<GitRemoteBranch> remoteBranches = new ArrayList<>();
        for (GitRemoteBranch branch : repo.getBranches().getRemoteBranches()) {
            if (branch.getRemote().equals(remote)) {
                remoteBranches.add(branch);
            }
        }
        remoteBranches.sort(null);

        List<GitBranch> branches = new ArrayList<>(repo.getBranches().getLocalBranches());
        branches.sort(null);
        branches.addAll(remoteBranches);
        branchModel.replaceAll(branches);
        branchComponent.setValue(repo.getCurrentBranch());
    }

    @RequiredUIAccess
    private static <T extends GitBranch> ComboBox<T> createBranchComboBox(FlatDataModel<T> branchModel) {
        ComboBox<T> comboBox = ComboBox.create(branchModel);
        comboBox.setTextRenderer(it -> it == null ? LocalizeValue.empty() : LocalizeValue.of(it.getName()));
        return comboBox;
    }

    @RequiredUIAccess
    private static JBPopup createBranchPopup(LocalizeValue repoRowMessage,
                                             Component repoComponent,
                                             Component branchComponent,
                                             boolean focusRepo,
                                             Runnable onSave) {
        JBPopup[] popupRef = new JBPopup[1];
        Button saveButton = Button.create(GitLocalize.branchDirectionPanelSaveButton(), event -> {
            onSave.run();
            JBPopup popup = popupRef[0];
            if (popup != null) {
                popup.closeOk(null);
            }
        });

        DockLayout buttonRow = DockLayout.create();
        buttonRow.right(saveButton);

        VerticalLayout panel = VerticalLayout.create();
        panel.add(LabeledBuilder.sided(repoRowMessage, repoComponent));
        panel.add(LabeledBuilder.sided(GitLocalize.branchDirectionPanelBranchLabel(), branchComponent));
        panel.add(buttonRow);

        JBPopup popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, focusRepo ? repoComponent : branchComponent)
            .setFocusable(true)
            .setRequestFocus(true)
            .createPopup();
        popupRef[0] = popup;
        return popup;
    }
}
