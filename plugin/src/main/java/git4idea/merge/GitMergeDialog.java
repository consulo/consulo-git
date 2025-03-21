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
package git4idea.merge;

import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.ElementsChooser;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitVcs;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandler;
import git4idea.commands.GitSimpleHandler;
import git4idea.util.GitUIUtil;
import jakarta.annotation.Nonnull;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.StringTokenizer;

/**
 * A dialog for merge action. It represents most options available for git merge.
 */
public class GitMergeDialog extends DialogWrapper {
    /**
     * The git root available for git merge action
     */
    private JComboBox myGitRoot;
    /**
     * The check box indicating that no commit will be created
     */
    private JCheckBox myNoCommitCheckBox;
    /**
     * The checkbox that suppresses fast forward resolution even if it is available
     */
    private JCheckBox myNoFastForwardCheckBox;
    /**
     * The checkbox that allows squashing all changes from branch into a single commit
     */
    private JCheckBox mySquashCommitCheckBox;
    /**
     * The label containing a name of the current branch
     */
    private JLabel myCurrentBranchText;
    /**
     * The panel containing a chooser of branches to merge
     */
    private JPanel myBranchToMergeContainer;
    /**
     * Chooser of branches to merge
     */
    private ElementsChooser<String> myBranchChooser;
    /**
     * The commit message
     */
    private JTextField myCommitMessage;
    /**
     * The strategy for merge
     */
    private JComboBox<GitMergeStrategy> myStrategy;
    /**
     * The panel
     */
    private JPanel myPanel;
    /**
     * The log information checkbox
     */
    private JCheckBox myAddLogInformationCheckBox;

    @Nonnull
    private final Project myProject;
    private final GitVcs myVcs;


    /**
     * A constructor
     *
     * @param project     a project to select
     * @param roots       a git repository roots for the project
     * @param defaultRoot a guessed default root
     */
    public GitMergeDialog(@Nonnull Project project, List<VirtualFile> roots, VirtualFile defaultRoot) {
        super(project, true);
        setTitle(GitLocalize.mergeBranchTitle());
        myProject = project;
        myVcs = GitVcs.getInstance(project);
        initBranchChooser();
        setOKActionEnabled(false);
        setOKButtonText(GitLocalize.mergeBranchButton());
        GitUIUtil.setupRootChooser(myProject, roots, defaultRoot, myGitRoot, myCurrentBranchText);
        GitUIUtil.imply(mySquashCommitCheckBox, true, myNoCommitCheckBox, true);
        GitUIUtil.imply(mySquashCommitCheckBox, true, myAddLogInformationCheckBox, false);
        GitUIUtil.implyDisabled(mySquashCommitCheckBox, true, myCommitMessage);
        GitUIUtil.exclusive(mySquashCommitCheckBox, true, myNoFastForwardCheckBox, true);
        myGitRoot.addActionListener(event -> {
            try {
                updateBranches();
            }
            catch (VcsException ex) {
                if (myVcs.getExecutableValidator().checkExecutableAndShowMessageIfNeeded(getRootPane())) {
                    myVcs.showErrors(List.of(ex), GitLocalize.mergeRetrievingBranches());
                }
            }
        });
        init();
    }

    /**
     * Initialize {@link #myBranchChooser} component
     */
    private void initBranchChooser() {
        myBranchChooser = new ElementsChooser<>(true);
        myBranchChooser.setToolTipText(GitLocalize.mergeBranchesTooltip().get());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 0, 0);
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        myBranchToMergeContainer.add(myBranchChooser, c);
        myStrategy.setRenderer(GitMergeStrategy.LIST_CELL_RENDERER);
        GitMergeUtil.setupStrategies(myBranchChooser, myStrategy);
        final ElementsChooser.ElementsMarkListener<String> listener =
            (element, isMarked) -> setOKActionEnabled(!myBranchChooser.getMarkedElements().isEmpty());
        listener.elementMarkChanged(null, true);
        myBranchChooser.addElementsMarkListener(listener);
    }


    /**
     * Setup branches for git root, this method should be called when root is changed.
     */
    public void updateBranches() throws VcsException {
        VirtualFile root = getSelectedRoot();
        GitSimpleHandler handler = new GitSimpleHandler(myProject, root, GitCommand.BRANCH);
        handler.setSilent(true);
        handler.addParameters("--no-color", "-a", "--no-merged");
        String output = handler.run();
        myBranchChooser.clear();
        for (StringTokenizer lines = new StringTokenizer(output, "\n", false); lines.hasMoreTokens(); ) {
            String branch = lines.nextToken().substring(2);
            myBranchChooser.addElement(branch, false);
        }
    }

    /**
     * @return get line handler configured according to the selected options
     */
    public GitLineHandler handler() {
        if (!isOK()) {
            throw new IllegalStateException("The handler could be retrieved only if dialog was completed successfully.");
        }
        VirtualFile root = (VirtualFile)myGitRoot.getSelectedItem();
        GitLineHandler h = new GitLineHandler(myProject, root, GitCommand.MERGE);
        // ignore merge failure
        h.ignoreErrorCode(1);
        if (myNoCommitCheckBox.isSelected()) {
            h.addParameters("--no-commit");
        }
        if (myAddLogInformationCheckBox.isSelected()) {
            h.addParameters("--log");
        }
        final String msg = myCommitMessage.getText().trim();
        if (msg.length() != 0) {
            h.addParameters("-m", msg);
        }
        if (mySquashCommitCheckBox.isSelected()) {
            h.addParameters("--squash");
        }
        if (myNoFastForwardCheckBox.isSelected()) {
            h.addParameters("--no-ff");
        }
        GitMergeStrategy strategy = (GitMergeStrategy)myStrategy.getSelectedItem();
        strategy.addParametersTo(h);
        for (String branch : myBranchChooser.getMarkedElements()) {
            h.addParameters(branch);
        }
        return h;
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
        return "reference.VersionControl.Git.MergeBranches";
    }

    /**
     * @return selected root
     */
    public VirtualFile getSelectedRoot() {
        return (VirtualFile)myGitRoot.getSelectedItem();
    }
}
