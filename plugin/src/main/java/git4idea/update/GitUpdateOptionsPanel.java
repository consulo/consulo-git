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
package git4idea.update;

import consulo.application.Application;
import consulo.git.localize.GitLocalize;
import consulo.ui.Component;
import consulo.ui.RadioButton;
import consulo.ui.RadioGroup;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.layout.LabeledLayout;
import consulo.ui.layout.VerticalLayout;
import git4idea.config.GitVcsSettings;
import git4idea.config.UpdateMethod;
import jakarta.annotation.Nonnull;

/**
 * Update options panel
 */
public class GitUpdateOptionsPanel {
    private VerticalLayout myPanel;

    private final RadioGroup<GitVcsSettings.UpdateChangesPolicy> mySavePolicyGroup;
    private final RadioGroup<UpdateMethod> mySaveUpdateGroup;

    @RequiredUIAccess
    public GitUpdateOptionsPanel() {
        myPanel = VerticalLayout.create();

        mySaveUpdateGroup = RadioGroup.create();

        RadioButton forceMergeRadioButton = mySaveUpdateGroup.newButton(GitLocalize.updateOptionsTypeMerge(), UpdateMethod.MERGE);
        forceMergeRadioButton.setToolTipText(GitLocalize.updateOptionsTypeMergeTooltip());

        RadioButton forceRebaseRadioButton = mySaveUpdateGroup.newButton(GitLocalize.updateOptionsTypeRebase(), UpdateMethod.REBASE);
        forceRebaseRadioButton.setToolTipText(GitLocalize.updateOptionsTypeRebaseTooltip());

        RadioButton branchDefaultRadioButton = mySaveUpdateGroup.newButton(GitLocalize.updateOptionsTypeDefault(), UpdateMethod.BRANCH_DEFAULT);
        branchDefaultRadioButton.setToolTipText(GitLocalize.updateOptionsTypeDefaultTooltip());

        mySaveUpdateGroup.setValue(UpdateMethod.BRANCH_DEFAULT);

        mySavePolicyGroup = RadioGroup.create();
        RadioButton stashRadioButton = mySavePolicyGroup.newButton(GitLocalize.updateOptionsSaveStash(), GitVcsSettings.UpdateChangesPolicy.STASH);
        stashRadioButton.setToolTipText(GitLocalize.updateOptionsSaveStashTooltip());

        RadioButton shelveRadioButton = mySavePolicyGroup.newButton(GitLocalize.updateOptionsSaveShelve(), GitVcsSettings.UpdateChangesPolicy.SHELVE);
        shelveRadioButton.setToolTipText(GitLocalize.updateOptionsSaveShelveTooltip(Application.get().getName()));

        mySavePolicyGroup.setValue(GitVcsSettings.UpdateChangesPolicy.STASH);

        VerticalLayout leftGroup = VerticalLayout.create()
            .add(forceMergeRadioButton)
            .add(forceRebaseRadioButton)
            .add(branchDefaultRadioButton);

        myPanel.add(LabeledLayout.create(GitLocalize.updateOptionsType(), leftGroup));

        VerticalLayout rightGroup = VerticalLayout.create()
            .add(stashRadioButton)
            .add(shelveRadioButton);

        myPanel.add(LabeledLayout.create(GitLocalize.updateOptionsSaveBeforeUpdate(), rightGroup));
    }

    @Nonnull
    public Component getPanel() {
        return myPanel;
    }

    @RequiredUIAccess
    public boolean isModified(GitVcsSettings settings) {
        UpdateMethod type = getUpdateType();
        return type != settings.getUpdateType() || updateSaveFilesPolicy() != settings.updateChangesPolicy();
    }

    /**
     * @return get policy value from selected radio buttons
     */
    @RequiredUIAccess
    private GitVcsSettings.UpdateChangesPolicy updateSaveFilesPolicy() {
        return mySavePolicyGroup.getValueOrError();
    }

    /**
     * @return get the currently selected update type
     */
    @RequiredUIAccess
    private UpdateMethod getUpdateType() {
        return mySaveUpdateGroup.getValueOrError();
    }

    /**
     * Save configuration to settings object
     */
    @RequiredUIAccess
    public void applyTo(GitVcsSettings settings) {
        settings.setUpdateType(getUpdateType());
        settings.setUpdateChangesPolicy(updateSaveFilesPolicy());
    }

    /**
     * Update panel according to settings
     */
    @RequiredUIAccess
    public void updateFrom(GitVcsSettings settings) {
        mySaveUpdateGroup.setValue(settings.getUpdateType());

        mySavePolicyGroup.setValue(settings.updateChangesPolicy());
    }
}
