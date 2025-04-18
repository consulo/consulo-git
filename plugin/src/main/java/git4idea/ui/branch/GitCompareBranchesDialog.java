/*
 * Copyright 2000-2011 JetBrains s.r.o.
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

import consulo.ide.impl.idea.ui.TabbedPaneImpl;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.TabbedPaneWrapper;
import consulo.ui.ex.awtUnsafe.TargetAWT;
import consulo.versionControlSystem.distributed.DvcsUtil;
import git4idea.GitUtil;
import git4idea.repo.GitRepository;
import git4idea.util.GitCommitCompareInfo;
import jakarta.annotation.Nonnull;

import javax.swing.*;

/**
 * Dialog for comparing two Git branches.
 *
 * @author Kirill Likhodedov
 */
public class GitCompareBranchesDialog extends DialogWrapper {
    private final Project myProject;
    private final String myBranchName;
    private final String myCurrentBranchName;
    private final GitCommitCompareInfo myCompareInfo;
    private final GitRepository myInitialRepo;
    private JPanel myLogPanel;

    public GitCompareBranchesDialog(
        @Nonnull Project project,
        @Nonnull String branchName,
        @Nonnull String currentBranchName,
        @Nonnull GitCommitCompareInfo compareInfo,
        @Nonnull GitRepository initialRepo
    ) {
        super(project, false);
        myCurrentBranchName = currentBranchName;
        myCompareInfo = compareInfo;
        myProject = project;
        myBranchName = branchName;
        myInitialRepo = initialRepo;

        String rootString;
        if (compareInfo.getRepositories().size() == 1 && GitUtil.getRepositoryManager(myProject).moreThanOneRoot()) {
            rootString = " in root " + DvcsUtil.getShortRepositoryName(initialRepo);
        }
        else {
            rootString = "";
        }
        setTitle(String.format("Comparing %s with %s%s", currentBranchName, branchName, rootString));
        setModal(false);
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        myLogPanel = new GitCompareBranchesLogPanel(myProject, myBranchName, myCurrentBranchName, myCompareInfo, myInitialRepo);
        JPanel diffPanel = new GitCompareBranchesDiffPanel(myProject, myBranchName, myCurrentBranchName, myCompareInfo);

        TabbedPaneImpl tabbedPane = new TabbedPaneImpl(SwingConstants.TOP);
        tabbedPane.addTab("Log", TargetAWT.to(PlatformIconGroup.vcsBranch()), myLogPanel);
        tabbedPane.addTab("Diff", TargetAWT.to(PlatformIconGroup.actionsDiff()), diffPanel);
        tabbedPane.setKeyboardNavigation(TabbedPaneWrapper.DEFAULT_PREV_NEXT_SHORTCUTS);
        return tabbedPane;
    }

    // it is information dialog - no need to OK or Cancel. Close the dialog by clicking the cross button or pressing Esc.
    @Nonnull
    @Override
    protected Action[] createActions() {
        return new Action[0];
    }

    @Override
    protected String getDimensionServiceKey() {
        return GitCompareBranchesDialog.class.getName();
    }

    @Override
    @RequiredUIAccess
    public JComponent getPreferredFocusedComponent() {
        return myLogPanel;
    }
}
