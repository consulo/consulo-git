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
package git4idea.ui;

import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.event.DocumentAdapter;
import consulo.util.io.FileUtil;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.commands.GitCommand;
import git4idea.commands.GitHandlerUtil;
import git4idea.commands.GitSimpleHandler;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.GitUIUtil;
import git4idea.util.StringScanner;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.io.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The tag dialog for the git
 */
public class GitTagDialog extends DialogWrapper {
    /**
     * Root panel
     */
    private JPanel myPanel;
    /**
     * Git root selector
     */
    private JComboBox myGitRootComboBox;
    /**
     * Current branch label
     */
    private JLabel myCurrentBranch;
    /**
     * Tag name
     */
    private JTextField myTagNameTextField;
    /**
     * Force tag creation checkbox
     */
    private JCheckBox myForceCheckBox;
    /**
     * Text area that contains tag message if non-empty
     */
    private JTextArea myMessageTextArea;
    /**
     * The name of commit to tag
     */
    private JTextField myCommitTextField;
    /**
     * The validate button
     */
    private JButton myValidateButton;
    /**
     * The validator for commit text field
     */
    private final GitReferenceValidator myCommitTextFieldValidator;
    /**
     * The current project
     */
    private final Project myProject;
    /**
     * Existing tags for the project
     */
    private final Set<String> myExistingTags = new HashSet<>();
    /**
     * Prefix for message file name
     */
    private static final String MESSAGE_FILE_PREFIX = "git-tag-message-";
    /**
     * Suffix for message file name
     */
    private static final String MESSAGE_FILE_SUFFIX = ".txt";
    /**
     * Encoding for the message file
     */
    private static final String MESSAGE_FILE_ENCODING = "UTF-8";

    /**
     * A constructor
     *
     * @param project     a project to select
     * @param roots       a git repository roots for the project
     * @param defaultRoot a guessed default root
     */
    public GitTagDialog(Project project, List<VirtualFile> roots, VirtualFile defaultRoot) {
        super(project, true);
        setTitle(GitLocalize.tagTitle());
        setOKButtonText(GitLocalize.tagButton());
        myProject = project;
        GitUIUtil.setupRootChooser(myProject, roots, defaultRoot, myGitRootComboBox, myCurrentBranch);
        myGitRootComboBox.addActionListener(e -> {
            fetchTags();
            validateFields();
        });
        fetchTags();
        myTagNameTextField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(final DocumentEvent e) {
                validateFields();
            }
        });
        myCommitTextFieldValidator =
            new GitReferenceValidator(project, myGitRootComboBox, myCommitTextField, myValidateButton, this::validateFields);
        myForceCheckBox.addActionListener(e -> {
            if (myForceCheckBox.isEnabled()) {
                validateFields();
            }
        });
        init();
        validateFields();
    }

    @Override
    @RequiredUIAccess
    public JComponent getPreferredFocusedComponent() {
        return myTagNameTextField;
    }

    /**
     * Perform tagging according to selected options
     *
     * @param exceptions the list where exceptions are collected
     */
    @RequiredUIAccess
    public void runAction(final List<VcsException> exceptions) {
        final String message = myMessageTextArea.getText();
        final boolean hasMessage = message.trim().length() != 0;
        final File messageFile;
        if (hasMessage) {
            try {
                messageFile = FileUtil.createTempFile(MESSAGE_FILE_PREFIX, MESSAGE_FILE_SUFFIX);
                messageFile.deleteOnExit();
                try (Writer out = new OutputStreamWriter(new FileOutputStream(messageFile), MESSAGE_FILE_ENCODING)) {
                    out.write(message);
                }
            }
            catch (IOException ex) {
                Messages.showErrorDialog(
                    myProject,
                    GitLocalize.tagErrorCreatingMessageFileMessage(ex.toString()).get(),
                    GitLocalize.tagErrorCreatingMessageFileTitle().get()
                );
                return;
            }
        }
        else {
            messageFile = null;
        }
        try {
            GitSimpleHandler h = new GitSimpleHandler(myProject, getGitRoot(), GitCommand.TAG);
            if (hasMessage) {
                h.addParameters("-a");
            }
            if (myForceCheckBox.isEnabled() && myForceCheckBox.isSelected()) {
                h.addParameters("-f");
            }
            if (hasMessage) {
                h.addParameters("-F", messageFile.getAbsolutePath());
            }
            h.addParameters(myTagNameTextField.getText());
            String object = myCommitTextField.getText().trim();
            if (object.length() != 0) {
                h.addParameters(object);
            }
            try {
                GitHandlerUtil.doSynchronously(h, GitLocalize.taggingTitle(), LocalizeValue.ofNullable(h.printableCommandLine()));
                VcsNotifier.getInstance(myProject).notifySuccess(
                    myTagNameTextField.getText(),
                    "Created tag " + myTagNameTextField.getText() + " successfully."
                );
            }
            finally {
                exceptions.addAll(h.errors());
                GitRepositoryManager manager = GitUtil.getRepositoryManager(myProject);
                manager.updateRepository(getGitRoot());
            }
        }
        finally {
            if (messageFile != null) {
                //noinspection ResultOfMethodCallIgnored
                messageFile.delete();
            }
        }
    }

    /**
     * Validate dialog fields
     */
    private void validateFields() {
        String text = myTagNameTextField.getText();
        if (myExistingTags.contains(text)) {
            myForceCheckBox.setEnabled(true);
            if (!myForceCheckBox.isSelected()) {
                setErrorText(GitLocalize.tagErrorTagExists());
                setOKActionEnabled(false);
                return;
            }
        }
        else {
            myForceCheckBox.setEnabled(false);
            myForceCheckBox.setSelected(false);
        }

        if (myCommitTextFieldValidator.isInvalid()) {
            setErrorText(GitLocalize.tagErrorInvalidCommit());
            setOKActionEnabled(false);
        }
        else if (text.isEmpty()) {
            clearErrorText();
            setOKActionEnabled(false);
        }
        else {
            clearErrorText();
            setOKActionEnabled(true);
        }
    }

    /**
     * Fetch tags
     */
    private void fetchTags() {
        myExistingTags.clear();
        GitSimpleHandler h = new GitSimpleHandler(myProject, getGitRoot(), GitCommand.TAG);
        h.setSilent(true);
        String output =
            GitHandlerUtil.doSynchronously(h, GitLocalize.tagGettingExistingTags(), LocalizeValue.ofNullable(h.printableCommandLine()));
        for (StringScanner s = new StringScanner(output); s.hasMoreData(); ) {
            String line = s.line();
            if (line.length() == 0) {
                continue;
            }
            myExistingTags.add(line);
        }
    }

    /**
     * @return the current git root
     */
    private VirtualFile getGitRoot() {
        return (VirtualFile)myGitRootComboBox.getSelectedItem();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected JComponent createCenterPanel() {
        return myPanel;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getDimensionServiceKey() {
        return getClass().getName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getHelpId() {
        return "reference.VersionControl.Git.TagFiles";
    }
}
