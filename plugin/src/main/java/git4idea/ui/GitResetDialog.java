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

import consulo.project.Project;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandler;
import git4idea.i18n.GitBundle;
import git4idea.util.GitUIUtil;

import javax.swing.*;
import java.util.List;

/**
 * The dialog for the "git reset" operation
 */
public class GitResetDialog extends DialogWrapper {
  /**
   * The --soft reset type
   */
  private static final String SOFT = GitBundle.message("reset.type.soft");
  /**
   * The --mixed reset type
   */
  private static final String MIXED = GitBundle.message("reset.type.mixed");
  /**
   * The --hard reset type
   */
  private static final String HARD = GitBundle.message("reset.type.hard");
  /**
   * Git root selector
   */
  private JComboBox myGitRootComboBox;
  /**
   * The label for the current branch
   */
  private JLabel myCurrentBranchLabel;
  /**
   * The selector for reset type
   */
  private JComboBox myResetTypeComboBox;
  /**
   * The text field that contains commit expressions
   */
  private JTextField myCommitTextField;
  /**
   * The validate button
   */
  private JButton myValidateButton;
  /**
   * The root panel for the dialog
   */
  private JPanel myPanel;

  /**
   * The project
   */
  private final Project myProject;
  /**
   * The validator for commit text
   */
  private final GitReferenceValidator myGitReferenceValidator;

  /**
   * A constructor
   *
   * @param project     the project
   * @param roots       the list of the roots
   * @param defaultRoot the default root to select
   */
  public GitResetDialog(final Project project, final List<VirtualFile> roots, final VirtualFile defaultRoot) {
    super(project, true);
    myProject = project;
    setTitle(GitBundle.message("reset.title"));
    setOKButtonText(GitBundle.message("reset.button"));
    myResetTypeComboBox.addItem(MIXED);
    myResetTypeComboBox.addItem(SOFT);
    myResetTypeComboBox.addItem(HARD);
    myResetTypeComboBox.setSelectedItem(MIXED);
    GitUIUtil.setupRootChooser(project, roots, defaultRoot, myGitRootComboBox, myCurrentBranchLabel);
    myGitReferenceValidator = new GitReferenceValidator(myProject, myGitRootComboBox, myCommitTextField, myValidateButton, new Runnable() {
      public void run() {
        validateFields();
      }
    });
    init();
  }

  /**
   * Validate
   */
  void validateFields() {
    if (myGitReferenceValidator.isInvalid()) {
      setErrorText(GitBundle.message("reset.commit.invalid"));
      setOKActionEnabled(false);
    }
    setErrorText(null);
    setOKActionEnabled(true);
  }

  /**
   * @return the handler for reset operation
   */
  public GitLineHandler handler() {
    GitLineHandler handler = new GitLineHandler(myProject, getGitRoot(), GitCommand.RESET);
    String type = (String)myResetTypeComboBox.getSelectedItem();
    if (SOFT.equals(type)) {
      handler.addParameters("--soft");
    }
    else if (HARD.equals(type)) {
      handler.addParameters("--hard");
    }
    else if (MIXED.equals(type)) {
      handler.addParameters("--mixed");
    }
    final String commit = myCommitTextField.getText().trim();
    if (commit.length() != 0) {
      handler.addParameters(commit);
    }
    handler.endOptions();
    return handler;
  }

  /**
   * @return the selected git root
   */
  public VirtualFile getGitRoot() {
    return (VirtualFile)myGitRootComboBox.getSelectedItem();
  }

  /**
   * {@inheritDoc}
   */
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
    return "gitResetHead";
  }
}
