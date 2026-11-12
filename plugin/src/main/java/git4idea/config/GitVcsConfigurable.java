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
package git4idea.config;

import consulo.annotation.component.ExtensionImpl;
import consulo.application.progress.Task;
import consulo.configurable.ProjectConfigurable;
import consulo.configurable.SimpleConfigurableByProperties;
import consulo.configurable.StandardConfigurableIds;
import consulo.disposer.Disposable;
import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.fileChooser.FileChooserTextBoxBuilder;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.process.cmd.ParametersListUtil;
import consulo.project.Project;
import consulo.util.lang.StringUtil;
import consulo.platform.Platform;
import consulo.ui.Alert;
import consulo.ui.Alerts;
import consulo.ui.Button;
import consulo.ui.CheckBox;
import consulo.ui.ComboBox;
import consulo.ui.Component;
import consulo.ui.Label;
import consulo.ui.MultiSelectComboBox;
import consulo.ui.TextBoxWithExpandAction;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.Presentation;
import consulo.ui.layout.DockLayout;
import consulo.ui.layout.HorizontalLayout;
import consulo.ui.layout.VerticalLayout;
import consulo.ui.util.Indenter;
import consulo.ui.util.LabeledBuilder;
import consulo.versionControlSystem.distributed.branch.DvcsSyncSettings;
import consulo.versionControlSystem.distributed.localize.DistributedVcsLocalize;
import git4idea.GitVcs;
import git4idea.push.GitForcePushType;
import git4idea.repo.GitRepositoryManager;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The Git page. Its check boxes are bound to the settings with method references, so they are switched from Find
 * Action too - keep them so.
 */
@ExtensionImpl
public class GitVcsConfigurable extends SimpleConfigurableByProperties implements ProjectConfigurable {
    private final Project myProject;
    private final GitVcsSettings mySettings;
    private final GitSharedSettings mySharedSettings;
    private final GitVcsApplicationSettings myAppSettings;

    @Inject
    public GitVcsConfigurable(Project project,
                              GitVcsSettings settings,
                              GitSharedSettings sharedSettings,
                              GitVcsApplicationSettings appSettings) {
        myProject = project;
        mySettings = settings;
        mySharedSettings = sharedSettings;
        myAppSettings = appSettings;
    }

    @Override
    public String getId() {
        return "vcs.git";
    }

    @Override
    public @Nullable String getParentId() {
        return StandardConfigurableIds.VCS_GROUP;
    }

    @Override
    public LocalizeValue getDisplayName() {
        return GitLocalize.git4ideaVcsName();
    }

    @Override
    public @Nullable String getHelpTopic() {
        return "project.propVCSSupport.VCSs.Git";
    }

    @RequiredUIAccess
    @Override
    protected Component createLayout(PropertyBuilder propertyBuilder, Disposable uiDisposable) {
        GitVcsSettings settings = mySettings;
        GitSharedSettings sharedSettings = mySharedSettings;
        GitVcsApplicationSettings appSettings = myAppSettings;
        GitVcs vcs = GitVcs.getInstance(myProject);

        VerticalLayout root = VerticalLayout.create();

        FileChooserTextBoxBuilder gitPathBuilder = FileChooserTextBoxBuilder.create(myProject);
        gitPathBuilder.uiDisposable(uiDisposable);
        gitPathBuilder.dialogTitle(GitLocalize.findGitTitle());
        gitPathBuilder.dialogDescription(GitLocalize.findGitDescription());
        gitPathBuilder.fileChooserDescriptor(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor());
        FileChooserTextBoxBuilder.Controller gitField = gitPathBuilder.build();
        // a remote project has the git of its platform - kept with the project, not in the settings of this machine
        boolean local = myProject.getPlatform() == Platform.current();
        propertyBuilder.add(
            () -> gitField.getValue().trim(),
            gitField::setValue,
            () -> local ? appSettings.getPathToGit() : StringUtil.notNullize(settings.getPathToGit()),
            path -> {
                if (local) {
                    appSettings.setPathToGit(path);
                }
                else {
                    settings.setPathToGit(StringUtil.nullize(path));
                }
                // run check later
                myProject.getUIAccess().give(() -> Task.Backgroundable.queue(
                    myProject,
                    "Checking Git Version...",
                    indicator -> vcs.checkVersion()
                ));
            }
        );
        Button testButton = Button.create(GitLocalize.cloneTest());
        testButton.addClickListener(e -> testConnection(root, gitField.getValue().trim()));
        root.add(DockLayout.create().left(Label.create(GitLocalize.gitVcsConfigPathLabel())).center(gitField).right(testButton));

        ComboBox<GitVcsApplicationSettings.SshExecutable> sshExecutable = ComboBox.create(GitVcsApplicationSettings.SshExecutable.values());
        sshExecutable.setTextRenderer(executable -> switch (executable) {
            case IDEA_SSH -> GitLocalize.gitVcsConfigSshModeIdea();
            case NATIVE_SSH -> GitLocalize.gitVcsConfigSshModeNative();
            case PUTTY -> GitLocalize.gitVcsConfigSshModePutty();
            default -> throw new IllegalArgumentException(executable.name());
        });
        propertyBuilder.add(sshExecutable, appSettings::getSshExecutableType, appSettings::setIdeaSsh);
        root.add(LabeledBuilder.sided(GitLocalize.gitVcsConfigSshMode(), sshExecutable));

        CheckBox syncControl = CheckBox.create(DistributedVcsLocalize.syncSetting());
        syncControl.setVisible(GitRepositoryManager.getInstance(myProject).moreThanOneRoot());
        propertyBuilder.add(
            syncControl,
            () -> settings.getSyncSetting() == DvcsSyncSettings.Value.SYNC,
            sync -> settings.setSyncSetting(sync ? DvcsSyncSettings.Value.SYNC : DvcsSyncSettings.Value.DONT_SYNC)
        );
        root.add(syncControl);

        CheckBox autoCommitOnCherryPick = CheckBox.create(GitLocalize.settingsCommitAutomaticallyOnCherryPick());
        propertyBuilder.add(autoCommitOnCherryPick, settings::isAutoCommitOnCherryPick, settings::setAutoCommitOnCherryPick);
        root.add(autoCommitOnCherryPick);

        CheckBox warnAboutCrlf = CheckBox.create(GitLocalize.settingsCrlf());
        propertyBuilder.add(warnAboutCrlf, settings::warnAboutCrlf, settings::setWarnAboutCrlf);
        root.add(warnAboutCrlf);

        CheckBox warnAboutDetachedHead = CheckBox.create(GitLocalize.settingsDetachedHead());
        propertyBuilder.add(warnAboutDetachedHead, settings::warnAboutDetachedHead, settings::setWarnAboutDetachedHead);
        root.add(warnAboutDetachedHead);

        ComboBox<UpdateMethod> updateMethod = ComboBox.create(UpdateMethod.values());
        propertyBuilder.add(updateMethod, settings::getUpdateType, settings::setUpdateType);
        Component updateMethodLabeled = LabeledBuilder.sided(GitLocalize.settingsUpdateMethod(), updateMethod);
        Indenter.indent(updateMethodLabeled);
        root.add(updateMethodLabeled);

        CheckBox autoUpdateIfPushRejected = CheckBox.create(GitLocalize.settingsAutoUpdateOnPushRejected());
        propertyBuilder.add(autoUpdateIfPushRejected, settings::autoUpdateIfPushRejected, settings::setAutoUpdateIfPushRejected);
        root.add(autoUpdateIfPushRejected);

        CheckBox enableForcePush = CheckBox.create(LocalizeValue.localizeTODO("Allow &force push"));
        propertyBuilder.add(enableForcePush, settings::isForcePushAllowed, settings::setForcePushAllowed);

        Label protectedBranchesLabel = Label.create(GitLocalize.settingsProtectedBranched());
        TextBoxWithExpandAction protectedBranches = TextBoxWithExpandAction.create(
            PlatformIconGroup.actionsShow(),
            "Protected Branches",
            ParametersListUtil.COLON_LINE_PARSER,
            ParametersListUtil.COLON_LINE_JOINER
        );
        propertyBuilder.add(
            protectedBranches,
            () -> ParametersListUtil.COLON_LINE_JOINER.apply(sharedSettings.getForcePushProhibitedPatterns()),
            text -> sharedSettings.setForcePushProhibitedPatters(ParametersListUtil.COLON_LINE_PARSER.apply(text))
        );
        root.add(
            DockLayout.create()
                .left(enableForcePush)
                .right(HorizontalLayout.create().add(protectedBranchesLabel).add(protectedBranches))
        );

        MultiSelectComboBox<GitForcePushType> forcePushTypes = createForcePushTypes(vcs.getVersion());
        propertyBuilder.add(
            () -> {
                // empty selection means defaults
                List<GitForcePushType> types = forcePushTypes.getValue();
                return types.isEmpty() ? GitForcePushType.DEFAULTS : types;
            },
            forcePushTypes::setValue,
            settings::getForcePushTypes,
            settings::setForcePushTypes
        );
        Component forcePushTypesLabeled = LabeledBuilder.sided(GitLocalize.settingsForcePushTypes(), forcePushTypes);
        Indenter.indent(forcePushTypesLabeled);
        root.add(forcePushTypesLabeled);

        enableForcePush.addValueListener(e -> {
            boolean enabled = e.getValue();
            protectedBranches.setEnabled(enabled);
            protectedBranchesLabel.setEnabled(enabled);
            forcePushTypes.setEnabled(enabled);
        });
        return root;
    }

    private static MultiSelectComboBox<GitForcePushType> createForcePushTypes(@Nullable GitVersion gitVersion) {
        MultiSelectComboBox<GitForcePushType> forcePushTypes = MultiSelectComboBox.create(GitForcePushType.values());
        forcePushTypes.setPlaceholder(GitLocalize.settingsForcePushTypesDefault());
        forcePushTypes.setRenderer((presentation, item) -> {
            GitForcePushType type = item.getValue();
            if (type == null) {
                return;
            }

            presentation.withTooltip(type.getDescription());

            LocalizeValue text = type.getActionText().map(Presentation.NO_MNEMONIC);
            String requiredVersion = type.getRequiredVersion();
            if (requiredVersion != null && !type.isSupported(gitVersion)) {
                presentation.append(GitLocalize.settingsForcePushTypeRequiresGit(text, requiredVersion));
            }
            else {
                presentation.append(text);
            }
        });
        forcePushTypes.setSummaryRenderer(types -> LocalizeValue.join(
            ", ",
            types.stream().map(type -> type.getActionText().map(Presentation.NO_MNEMONIC)).toArray(LocalizeValue[]::new)
        ));
        return forcePushTypes;
    }

    /**
     * Test availability of the connection
     */
    @RequiredUIAccess
    private void testConnection(Component parent, String executable) {
        if (myProject.getPlatform() == Platform.current()) {
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
                    version = GitVersion.identifyVersion(myProject.getPlatform(), executable.isEmpty() ? "git" : executable);
                }
                catch (Exception e) {
                    uiAccess.give(() -> Alerts.okInfo(LocalizeValue.of(e.getLocalizedMessage()))
                        .title(GitLocalize.findGitErrorTitle())
                        .showAsync(parent));
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
                    alert.showAsync(parent);
                });
            }
        );
    }
}
