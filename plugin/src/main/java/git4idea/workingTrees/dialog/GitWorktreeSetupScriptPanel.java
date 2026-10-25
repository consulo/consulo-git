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

import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.fileChooser.FileChooserTextBoxBuilder;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.CheckBox;
import consulo.ui.Component;
import consulo.ui.Label;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.layout.VerticalLayout;
import consulo.ui.style.ComponentColors;
import consulo.ui.util.FormBuilder;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Run setup script" section of the New Worktree dialog. Call {@link #buildPanel} once to add its rows.
 */
public final class GitWorktreeSetupScriptPanel {
    private final Project myProject;
    private final boolean myInitialRunSetupScript;
    private final String myInitialSetupScriptPath;
    private final List<Runnable> myListeners = new ArrayList<>();

    private @Nullable CheckBox myRunSetupScript;
    private FileChooserTextBoxBuilder.@Nullable Controller mySetupScriptPath;

    public GitWorktreeSetupScriptPanel(@Nullable GitWorkingTreeDialogState savedState, Project project) {
        myProject = project;
        myInitialRunSetupScript = savedState != null && savedState.runSetupScript();
        myInitialSetupScriptPath = savedState != null ? savedState.setupScriptPath() : "";
    }

    public void addChangeListener(Runnable listener) {
        myListeners.add(listener);
    }

    @RequiredUIAccess
    public void buildPanel(VerticalLayout panel) {
        CheckBox runSetupScript = CheckBox.create(GitLocalize.workingTreeDialogCheckboxSetupScript());
        runSetupScript.setValue(myInitialRunSetupScript);
        myRunSetupScript = runSetupScript;
        panel.add(runSetupScript);

        FileChooserTextBoxBuilder builder = FileChooserTextBoxBuilder.create(myProject);
        builder.fileChooserDescriptor(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor());
        builder.dialogTitle(GitLocalize.workingTreeDialogLabelSetupScriptFileChooserTitle());
        FileChooserTextBoxBuilder.Controller pathController = builder.build();
        pathController.setValue(myInitialSetupScriptPath);
        mySetupScriptPath = pathController;

        Label comment = Label.create(GitLocalize.workingTreeDialogSetupScriptComment());
        comment.setForegroundColor(ComponentColors.DISABLED_TEXT);

        VerticalLayout scriptRow = VerticalLayout.create();
        scriptRow.add(FormBuilder.create().addLabeled(GitLocalize.workingTreeDialogLabelSetupScript(), pathController.getComponent()).build());
        scriptRow.add(comment);
        scriptRow.setVisible(runSetupScript.getValueOrError());
        panel.add(scriptRow);

        runSetupScript.addValueListener(event -> {
            scriptRow.setVisible(event.getValue());
            myListeners.forEach(Runnable::run);
        });
        pathController.getComponent().addValueListener(event -> myListeners.forEach(Runnable::run));
    }

    public boolean isRunSetupScript() {
        CheckBox checkBox = myRunSetupScript;
        return checkBox != null ? checkBox.getValueOrError() : myInitialRunSetupScript;
    }

    public String getSetupScriptPath() {
        FileChooserTextBoxBuilder.Controller controller = mySetupScriptPath;
        return controller != null ? controller.getValue() : myInitialSetupScriptPath;
    }

    public LocalizeValue validateSetupScriptPath() {
        if (!isRunSetupScript()) {
            return LocalizeValue.empty();
        }
        if (getSetupScriptPath().isBlank()) {
            return GitLocalize.workingTreeDialogSetupScriptValidationEmpty();
        }
        Path path = parseAbsolutePath(getSetupScriptPath());
        if (path == null) {
            return GitLocalize.workingTreeDialogSetupScriptValidationNotAbsolute();
        }
        if (!Files.isRegularFile(path)) {
            return GitLocalize.workingTreeDialogSetupScriptValidationNotAFile();
        }
        return LocalizeValue.empty();
    }

    /**
     * Returns the validated, absolute setup-script path, or {@code null} when the script is off or the path fails validation.
     */
    public @Nullable Path getValidatedScriptPath() {
        if (!isRunSetupScript()) {
            return null;
        }
        Path path = parseAbsolutePath(getSetupScriptPath());
        return path != null && Files.isRegularFile(path) ? path : null;
    }

    /**
     * Parses {@code text} as a path. Returns {@code null} for a malformed or a relative path.
     */
    private static @Nullable Path parseAbsolutePath(String text) {
        Path path;
        try {
            path = Path.of(text);
        }
        catch (InvalidPathException e) {
            return null;
        }
        return path.isAbsolute() ? path : null;
    }
}
