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
package git4idea.branch;

import consulo.application.Application;
import consulo.project.Project;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.IdeBorderFactory;
import consulo.ui.ex.awt.JBLabel;
import consulo.ui.ex.awt.UIUtil;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static consulo.util.lang.StringUtil.capitalize;

/**
 * The dialog that is shown when the error
 * "Your local changes to the following files would be overwritten by merge/checkout"
 * happens.
 * Displays the list of these files and proposes to make a "smart" merge or checkout.
 */
public class GitSmartOperationDialog extends DialogWrapper {
    public static final int SMART_EXIT_CODE = OK_EXIT_CODE;
    public static final int FORCE_EXIT_CODE = NEXT_USER_EXIT_CODE;

    @Nonnull
    private final JComponent myFileBrowser;
    @Nonnull
    private final String myOperationTitle;
    @Nullable
    private final String myForceButton;

    /**
     * Shows the dialog with the list of local changes preventing merge/checkout and returns the dialog exit code.
     */
    static int showAndGetAnswer(
        @Nonnull final Project project,
        @Nonnull final JComponent fileBrowser,
        @Nonnull final String operationTitle,
        @Nullable final String forceButtonTitle
    ) {
        final AtomicInteger exitCode = new AtomicInteger();
        UIUtil.invokeAndWaitIfNeeded((Runnable)() -> {
            GitSmartOperationDialog dialog = new GitSmartOperationDialog(project, fileBrowser, operationTitle, forceButtonTitle);
            dialog.show();
            exitCode.set(dialog.getExitCode());
        });
        return exitCode.get();
    }

    private GitSmartOperationDialog(
        @Nonnull Project project,
        @Nonnull JComponent fileBrowser,
        @Nonnull String operationTitle,
        @Nullable String forceButton
    ) {
        super(project);
        myFileBrowser = fileBrowser;
        myOperationTitle = operationTitle;
        myForceButton = forceButton;
        String capitalizedOperation = capitalize(myOperationTitle);
        setTitle("Git " + capitalizedOperation + " Problem");

        setOKButtonText("Smart " + capitalizedOperation);
        getOKAction().putValue(Action.SHORT_DESCRIPTION, "Stash local changes, " + operationTitle + ", unstash");
        setCancelButtonText("Don't " + capitalizedOperation);
        getCancelAction().putValue(FOCUSED_ACTION, Boolean.TRUE);
        init();
    }

    @Nonnull
    @Override
    protected Action[] createLeftSideActions() {
        if (myForceButton != null) {
            return new Action[]{new ForceCheckoutAction(myForceButton, myOperationTitle)};
        }
        return new Action[0];
    }

    @Override
    protected JComponent createNorthPanel() {
        JBLabel description = new JBLabel("<html>Your local changes to the following files would be overwritten by " + myOperationTitle +
            ".<br/>" + Application.get().getName() + " can stash the changes, " +
            "" + myOperationTitle + " and unstash them after that.</html>");
        description.setBorder(IdeBorderFactory.createEmptyBorder(0, 0, 10, 0));
        return description;
    }

    @Override
    protected JComponent createCenterPanel() {
        return myFileBrowser;
    }

    @Override
    protected String getDimensionServiceKey() {
        return GitSmartOperationDialog.class.getName();
    }

    private class ForceCheckoutAction extends AbstractAction {

        ForceCheckoutAction(@Nonnull String buttonTitle, @Nonnull String operationTitle) {
            super(buttonTitle);
            putValue(Action.SHORT_DESCRIPTION, capitalize(operationTitle) + " and overwrite local changes");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            close(FORCE_EXIT_CODE);
        }
    }
}
