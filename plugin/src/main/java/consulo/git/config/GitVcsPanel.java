/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package consulo.git.config;

import consulo.application.progress.Task;
import consulo.disposer.Disposable;
import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.fileChooser.FileChooserTextBoxBuilder;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.process.cmd.ParametersListUtil;
import consulo.project.Project;
import consulo.ui.*;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.Presentation;
import consulo.ui.layout.DockLayout;
import consulo.ui.layout.HorizontalLayout;
import consulo.ui.layout.VerticalLayout;
import consulo.ui.util.Indenter;
import consulo.ui.util.LabeledBuilder;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.distributed.branch.DvcsSyncSettings;
import consulo.versionControlSystem.distributed.localize.DistributedVcsLocalize;
import git4idea.GitVcs;
import git4idea.config.*;
import git4idea.push.GitForcePushType;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;

import java.util.List;

/**
 * Git VCS configuration panel
 */
public class GitVcsPanel {
    private final GitVcsApplicationSettings myAppSettings;
    private final GitVcs myVcs;

    private final VerticalLayout myRootPanel;
    private final FileChooserTextBoxBuilder.Controller myGitField;
    private final ComboBox<GitVcsApplicationSettings.SshExecutable> mySSHExecutableComboBox; // Type of SSH executable to use
    private final CheckBox myAutoUpdateIfPushRejected;
    private final CheckBox mySyncControl;
    private final CheckBox myAutoCommitOnCherryPick;
    private final CheckBox myWarnAboutCrlf;
    private final CheckBox myWarnAboutDetachedHead;
    private final CheckBox myEnableForcePush;
    private final TextBoxWithExpandAction myProtectedBranchesButton;
    private final Label myProtectedBranchesLabel;
    private final MultiSelectComboBox<GitForcePushType> myForcePushTypes;
    private final ComboBox<UpdateMethod> myUpdateMethodComboBox;

    @Nonnull
    private final Project myProject;

    @RequiredUIAccess
    public GitVcsPanel(@Nonnull Project project, @Nonnull Disposable uiDisposable) {
        myProject = project;
        myVcs = GitVcs.getInstance(project);
        myAppSettings = GitVcsApplicationSettings.getInstance();
        myRootPanel = VerticalLayout.create();

        FileChooserTextBoxBuilder gitPathBuilder = FileChooserTextBoxBuilder.create(project);
        gitPathBuilder.uiDisposable(uiDisposable);
        gitPathBuilder.dialogTitle(GitLocalize.findGitTitle());
        gitPathBuilder.dialogDescription(GitLocalize.findGitDescription());
        gitPathBuilder.fileChooserDescriptor(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor());

        myAutoUpdateIfPushRejected = CheckBox.create(GitLocalize.settingsAutoUpdateOnPushRejected());
        myEnableForcePush = CheckBox.create(LocalizeValue.localizeTODO("Allow &force push"));
        mySyncControl = CheckBox.create(DistributedVcsLocalize.syncSetting());
        myAutoCommitOnCherryPick = CheckBox.create(GitLocalize.settingsCommitAutomaticallyOnCherryPick());
        myWarnAboutCrlf = CheckBox.create(GitLocalize.settingsCrlf());
        myWarnAboutDetachedHead = CheckBox.create(GitLocalize.settingsDetachedHead());

        myGitField = gitPathBuilder.build();

        mySSHExecutableComboBox = ComboBox.create(GitVcsApplicationSettings.SshExecutable.values());
        mySSHExecutableComboBox.setValue(GitVcsApplicationSettings.SshExecutable.IDEA_SSH);
        mySSHExecutableComboBox.setTextRenderer(sshExecutable -> switch (sshExecutable) {
            case IDEA_SSH -> GitLocalize.gitVcsConfigSshModeIdea();
            case NATIVE_SSH -> GitLocalize.gitVcsConfigSshModeNative();
            case PUTTY -> GitLocalize.gitVcsConfigSshModePutty();
            default -> throw new IllegalArgumentException(sshExecutable.name());
        });

        myUpdateMethodComboBox = ComboBox.create(UpdateMethod.values());
        myUpdateMethodComboBox.selectFirst();

        Button testButton = Button.create(GitLocalize.cloneTest());
        testButton.addClickListener(e -> testConnection());
        GitRepositoryManager repositoryManager = GitRepositoryManager.getInstance(project);
        mySyncControl.setVisible(repositoryManager.moreThanOneRoot());

        myProtectedBranchesLabel = Label.create(GitLocalize.settingsProtectedBranched());
        myProtectedBranchesButton = TextBoxWithExpandAction.create(
            PlatformIconGroup.actionsShow(),
            "Protected Branches",
            ParametersListUtil.COLON_LINE_PARSER,
            ParametersListUtil.COLON_LINE_JOINER
        );

        GitVersion gitVersion = myVcs.getVersion();
        myForcePushTypes = MultiSelectComboBox.create(GitForcePushType.values());
        myForcePushTypes.setPlaceholder(GitLocalize.settingsForcePushTypesDefault());
        myForcePushTypes.setTextRenderer(type -> {
            if (type == null) {
                return LocalizeValue.empty();
            }
            LocalizeValue text = type.getActionText().map(Presentation.NO_MNEMONIC);
            String requiredVersion = type.getRequiredVersion();
            if (requiredVersion != null && !type.isSupported(gitVersion)) {
                return GitLocalize.settingsForcePushTypeRequiresGit(text, requiredVersion);
            }
            return text;
        });
        myForcePushTypes.setSummaryRenderer(types -> LocalizeValue.join(
            ", ",
            types.stream().map(type -> type.getActionText().map(Presentation.NO_MNEMONIC)).toArray(LocalizeValue[]::new)
        ));

        myEnableForcePush.addValueListener(e -> updateForcePushComponents());

        myRootPanel.add(DockLayout.create().left(Label.create(GitLocalize.gitVcsConfigPathLabel())).center(myGitField).right(testButton));
        myRootPanel.add(LabeledBuilder.sided(GitLocalize.gitVcsConfigSshMode(), mySSHExecutableComboBox));
        myRootPanel.add(mySyncControl);
        myRootPanel.add(myAutoCommitOnCherryPick);
        myRootPanel.add(myWarnAboutCrlf);
        myRootPanel.add(myWarnAboutDetachedHead);
        Component updateMethodLabeled = LabeledBuilder.sided(GitLocalize.settingsUpdateMethod(), myUpdateMethodComboBox);
        Indenter.indent(updateMethodLabeled);
        myRootPanel.add(updateMethodLabeled);
        myRootPanel.add(myAutoUpdateIfPushRejected);
        myRootPanel.add(
            DockLayout.create()
                .left(myEnableForcePush)
                .right(HorizontalLayout.create().add(myProtectedBranchesLabel).add(myProtectedBranchesButton))
        );
        Component forcePushTypesLabeled = LabeledBuilder.sided(GitLocalize.settingsForcePushTypes(), myForcePushTypes);
        Indenter.indent(forcePushTypesLabeled);
        myRootPanel.add(forcePushTypesLabeled);
    }

    /**
     * Test availability of the connection
     */
    @RequiredUIAccess
    private void testConnection() {
        String executable = getCurrentExecutablePath();
        if (myAppSettings != null) {
            myAppSettings.setPathToGit(executable);
        }

        UIAccess uiAccess = UIAccess.current();

        Task.Backgroundable.queue(
            myProject,
            GitLocalize.cloneDialogCheckingGitVersion(),
            true,
            indicator -> {
                GitVersion version;
                try {
                    version = GitVersion.identifyVersion(executable);
                }
                catch (Exception e) {
                    uiAccess.give(() -> Alerts.okInfo(LocalizeValue.of(e.getLocalizedMessage()))
                        .title(GitLocalize.findGitErrorTitle())
                        .showAsync(myRootPanel));
                    return;
                }

                uiAccess.give(() -> {
                    Alert<Object> alert;
                    if (version.isSupported()) {
                        alert = Alerts.okInfo(GitLocalize.gitExecutableVersionIs(version));
                    }
                    else {
                        alert = Alerts.okWarning(GitLocalize.findGitUnsupportedMessage(version, GitVersion.MIN));
                    }

                    alert = alert.title(GitLocalize.findGitSuccessTitle());
                    alert.showAsync(myRootPanel);
                });
            }
        );
    }

    @RequiredUIAccess
    private String getCurrentExecutablePath() {
        return myGitField.getValue().trim();
    }

    /**
     * @return the configuration panel
     */
    public VerticalLayout getPanel() {
        return myRootPanel;
    }

    /**
     * Load settings into the configuration panel
     *
     * @param settings the settings to load
     */
    @RequiredUIAccess
    public void load(@Nonnull GitVcsSettings settings, @Nonnull GitSharedSettings sharedSettings) {
        myGitField.setValue(settings.getAppSettings().getPathToGit());
        mySSHExecutableComboBox.setValue(settings.getAppSettings().getSshExecutableType());
        myAutoUpdateIfPushRejected.setValue(settings.autoUpdateIfPushRejected());
        mySyncControl.setValue(settings.getSyncSetting() == DvcsSyncSettings.Value.SYNC);
        myAutoCommitOnCherryPick.setValue(settings.isAutoCommitOnCherryPick());
        myWarnAboutCrlf.setValue(settings.warnAboutCrlf());
        myWarnAboutDetachedHead.setValue(settings.warnAboutDetachedHead());
        myEnableForcePush.setValue(settings.isForcePushAllowed());
        myUpdateMethodComboBox.setValue(settings.getUpdateType());
        myForcePushTypes.setValue(settings.getForcePushTypes());
        updateForcePushComponents();
        myProtectedBranchesButton.setValue(ParametersListUtil.COLON_LINE_JOINER.apply(sharedSettings.getForcePushProhibitedPatterns()));
    }

    /**
     * Check if fields has been modified with respect to settings object
     *
     * @param settings the settings to load
     */
    @RequiredUIAccess
    public boolean isModified(@Nonnull GitVcsSettings settings, @Nonnull GitSharedSettings sharedSettings) {
        return !settings.getAppSettings().getPathToGit().equals(getCurrentExecutablePath())
            || settings.getAppSettings().getSshExecutableType() != mySSHExecutableComboBox.getValueOrError()
            || !settings.autoUpdateIfPushRejected() == myAutoUpdateIfPushRejected.getValueOrError()
            || (settings.getSyncSetting() == DvcsSyncSettings.Value.SYNC) != mySyncControl.getValueOrError()
            || settings.isAutoCommitOnCherryPick() != myAutoCommitOnCherryPick.getValueOrError()
            || settings.warnAboutCrlf() != myWarnAboutCrlf.getValueOrError()
            || settings.warnAboutDetachedHead() != myWarnAboutDetachedHead.getValueOrError()
            || settings.isForcePushAllowed() != myEnableForcePush.getValueOrError()
            || settings.getUpdateType() != myUpdateMethodComboBox.getValueOrError()
            || !settings.getForcePushTypes().equals(getSelectedForcePushTypes())
            || !ContainerUtil.sorted(sharedSettings.getForcePushProhibitedPatterns())
            .equals(ContainerUtil.sorted(getProtectedBranchesPatterns()));
    }

    /**
     * Save configuration panel state into settings object
     *
     * @param settings the settings object
     */
    @RequiredUIAccess
    public void save(@Nonnull GitVcsSettings settings, GitSharedSettings sharedSettings) {
        settings.getAppSettings().setPathToGit(getCurrentExecutablePath());
        // run check later
        myProject.getUIAccess().give(() -> Task.Backgroundable.queue(
            myProject,
            "Checking Git Version...",
            progressIndicator -> myVcs.checkVersion()
        ));
        settings.getAppSettings().setIdeaSsh(mySSHExecutableComboBox.getValueOrError());
        settings.setAutoUpdateIfPushRejected(myAutoUpdateIfPushRejected.getValueOrError());

        settings.setSyncSetting(mySyncControl.getValueOrError() ? DvcsSyncSettings.Value.SYNC : DvcsSyncSettings.Value.DONT_SYNC);
        settings.setAutoCommitOnCherryPick(myAutoCommitOnCherryPick.getValueOrError());
        settings.setWarnAboutCrlf(myWarnAboutCrlf.getValueOrError());
        settings.setWarnAboutDetachedHead(myWarnAboutDetachedHead.getValueOrError());
        settings.setForcePushAllowed(myEnableForcePush.getValueOrError());
        settings.setUpdateType(myUpdateMethodComboBox.getValueOrError());
        settings.setForcePushTypes(myForcePushTypes.getValue());
        sharedSettings.setForcePushProhibitedPatters(getProtectedBranchesPatterns());
    }

    @RequiredUIAccess
    private void updateForcePushComponents() {
        boolean enabled = myEnableForcePush.getValueOrError();
        myProtectedBranchesButton.setEnabled(enabled);
        myProtectedBranchesLabel.setEnabled(enabled);
        myForcePushTypes.setEnabled(enabled);
    }

    /**
     * @return selected types, empty selection means defaults
     */
    @Nonnull
    @RequiredUIAccess
    private List<GitForcePushType> getSelectedForcePushTypes() {
        List<GitForcePushType> types = myForcePushTypes.getValue();
        return types.isEmpty() ? GitForcePushType.DEFAULTS : types;
    }

    @Nonnull
    @RequiredUIAccess
    private List<String> getProtectedBranchesPatterns() {
        return ParametersListUtil.COLON_LINE_PARSER.apply(myProtectedBranchesButton.getValueOrError());
    }
}
