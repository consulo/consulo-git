/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package git4idea.checkin;

import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.*;
import consulo.util.lang.Couple;
import consulo.util.lang.StringUtil;
import consulo.util.lang.SystemProperties;
import consulo.util.lang.xml.XmlStringUtil;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import static consulo.ui.ex.awt.UIUtil.DEFAULT_HGAP;
import static consulo.ui.ex.awt.UIUtil.DEFAULT_VGAP;
import static consulo.util.lang.StringUtil.isEmptyOrSpaces;

/**
 * @author Kirill Likhodedov
 */
class GitUserNameNotDefinedDialog extends DialogWrapper {
    @Nonnull
    private final Collection<VirtualFile> myRootsWithUndefinedProps;
    @Nonnull
    private final Collection<VirtualFile> myAllRootsAffectedByCommit;
    @Nullable
    private final Couple<String> myProposedValues;

    private JTextField myNameTextField;
    private JTextField myEmailTextField;
    private JBCheckBox myGlobalCheckbox;

    GitUserNameNotDefinedDialog(
        @Nonnull Project project,
        @Nonnull Collection<VirtualFile> rootsWithUndefinedProps,
        @Nonnull Collection<VirtualFile> allRootsAffectedByCommit,
        @Nonnull Map<VirtualFile, Couple<String>> rootsWithDefinedProps
    ) {
        super(project, false);
        myRootsWithUndefinedProps = rootsWithUndefinedProps;
        myAllRootsAffectedByCommit = allRootsAffectedByCommit;

        myProposedValues = calcProposedValues(rootsWithDefinedProps);

        setTitle("Git User Name Is Not Defined");
        setOKButtonText("Set and Commit");

        init();
    }

    @Override
    @RequiredUIAccess
    protected ValidationInfo doValidate() {
        String message = "You have to specify user name and email for Git";
        if (isEmptyOrSpaces(getUserName())) {
            return new ValidationInfo(message, myNameTextField);
        }
        if (isEmptyOrSpaces(getUserEmail())) {
            return new ValidationInfo(message, myEmailTextField);
        }
        return null;
    }

    @Override
    @RequiredUIAccess
    public JComponent getPreferredFocusedComponent() {
        return myNameTextField;
    }

    @Nullable
    private static Couple<String> calcProposedValues(Map<VirtualFile, Couple<String>> rootsWithDefinedProps) {
        if (rootsWithDefinedProps.isEmpty()) {
            return null;
        }
        Iterator<Map.Entry<VirtualFile, Couple<String>>> iterator = rootsWithDefinedProps.entrySet().iterator();
        Couple<String> firstValue = iterator.next().getValue();
        while (iterator.hasNext()) {
            // nothing to propose if there are different values set in different repositories
            if (!firstValue.equals(iterator.next().getValue())) {
                return null;
            }
        }
        return firstValue;
    }

    @Override
    protected JComponent createCenterPanel() {
        JLabel icon = new JBLabel(UIUtil.getWarningIcon(), SwingConstants.LEFT);
        JLabel description = new JLabel(getMessageText());

        myNameTextField = new JTextField(20);
        JBLabel nameLabel = new JBLabel("Name: ");
        nameLabel.setDisplayedMnemonic('n');
        nameLabel.setLabelFor(myNameTextField);

        myEmailTextField = new JTextField(20);
        JBLabel emailLabel = new JBLabel("E-mail: ");
        emailLabel.setDisplayedMnemonic('e');
        emailLabel.setLabelFor(myEmailTextField);

        if (myProposedValues != null) {
            myNameTextField.setText(myProposedValues.getFirst());
            myEmailTextField.setText(myProposedValues.getSecond());
        }
        else {
            myNameTextField.setText(SystemProperties.getUserName());
        }

        myGlobalCheckbox = new JBCheckBox("Set properties globally", true);
        myGlobalCheckbox.setMnemonic('g');

        JPanel rootPanel = new JPanel(new GridBagLayout());
        GridBag g = new GridBag()
            .setDefaultInsets(new Insets(0, 0, DEFAULT_VGAP, DEFAULT_HGAP))
            .setDefaultAnchor(GridBagConstraints.LINE_START)
            .setDefaultFill(GridBagConstraints.HORIZONTAL);

        rootPanel.add(description, g.nextLine().next().coverLine(3).pady(DEFAULT_HGAP));
        rootPanel.add(icon, g.nextLine().next().coverColumn(3));
        rootPanel.add(nameLabel, g.next().fillCellNone().insets(new Insets(0, 6, DEFAULT_VGAP, DEFAULT_HGAP)));
        rootPanel.add(myNameTextField, g.next());
        rootPanel.add(emailLabel, g.nextLine().next().next().fillCellNone().insets(new Insets(0, 6, DEFAULT_VGAP, DEFAULT_HGAP)));
        rootPanel.add(myEmailTextField, g.next());
        rootPanel.add(myGlobalCheckbox, g.nextLine().next().next().coverLine(2));

        return rootPanel;
    }

    @Override
    protected JComponent createNorthPanel() {
        return null;
    }

    @Nonnull
    private String getMessageText() {
        if (myAllRootsAffectedByCommit.size() == myRootsWithUndefinedProps.size()) {
            return "";
        }
        String text = "Git user.name and user.email properties are not defined in " +
            StringUtil.pluralize("root", myRootsWithUndefinedProps.size()) + "<br/>";
        for (VirtualFile root : myRootsWithUndefinedProps) {
            text += root.getPresentableUrl() + "<br/>";
        }
        return XmlStringUtil.wrapInHtml(text);
    }

    public String getUserName() {
        return myNameTextField.getText();
    }

    public String getUserEmail() {
        return myEmailTextField.getText();
    }

    public boolean isGlobal() {
        return myGlobalCheckbox.isSelected();
    }
}
