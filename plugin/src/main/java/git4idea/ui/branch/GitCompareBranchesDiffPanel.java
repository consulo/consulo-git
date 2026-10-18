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
package git4idea.ui.branch;

import consulo.disposer.Disposable;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.JBLabel;
import consulo.ui.ex.awt.UIUtil;
import consulo.ui.ex.awtUnsafe.TargetAWT;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.ui.VcsChangesBrowser;
import consulo.versionControlSystem.ui.VcsChangesDialogFactory;
import git4idea.util.GitCommitCompareInfo;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * @author Kirill Likhodedov
 */
class GitCompareBranchesDiffPanel extends JPanel {
    private final Project myProject;
    private final String myBranchName;
    private final String myCurrentBranchName;
    private final GitCommitCompareInfo myCompareInfo;

    @RequiredUIAccess
    public GitCompareBranchesDiffPanel(
        Project project,
        String branchName,
        String currentBranchName,
        GitCommitCompareInfo compareInfo,
        Disposable parentDisposable
    ) {
        super();

        myProject = project;
        myCurrentBranchName = currentBranchName;
        myCompareInfo = compareInfo;
        myBranchName = branchName;

        setLayout(new BorderLayout(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP));
        add(createNorthPanel(), BorderLayout.NORTH);
        add(createCenterPanel(parentDisposable));
    }

    private JComponent createNorthPanel() {
        return new JBLabel(
            String.format(
                "<html>Difference between current working tree on <b><code>%s</code></b>" +
                    " and files in <b><code>%s</code></b>:</html>",
                myCurrentBranchName,
                myBranchName
            ),
            UIUtil.ComponentStyle.REGULAR
        );
    }

    @RequiredUIAccess
    private JComponent createCenterPanel(Disposable parentDisposable) {
        List<Change> diff = myCompareInfo.getTotalDiff();
        VcsChangesBrowser changesBrowser = myProject.getApplication().getInstance(VcsChangesDialogFactory.class)
            .changesBrowser(myProject)
            .build(parentDisposable);
        changesBrowser.setChanges(diff);
        return (JComponent) TargetAWT.to(changesBrowser.getComponent());
    }
}
