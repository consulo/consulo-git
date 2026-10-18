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
package git4idea.ui;

import consulo.disposer.Disposable;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.Splitter;
import consulo.ui.ex.awtUnsafe.TargetAWT;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.ui.VcsChangesBrowser;
import consulo.versionControlSystem.ui.VcsChangesDialogFactory;
import git4idea.GitCommit;
import jakarta.annotation.Nonnull;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * List of commits at the left, the {@link VcsChangesBrowser} at the right.
 * Select a commit to shows its changes in the changes browser.
 *
 * @author Kirill Likhodedov
 */
public class GitCommitListWithDiffPanel extends JPanel {
    private final VcsChangesBrowser myChangesBrowser;
    private final GitCommitListPanel myCommitListPanel;

    @RequiredUIAccess
    public GitCommitListWithDiffPanel(@Nonnull Project project, @Nonnull List<GitCommit> commits, @Nonnull Disposable parentDisposable) {
        super(new BorderLayout());

        myCommitListPanel = new GitCommitListPanel(commits, null);
        myCommitListPanel.addListMultipleSelectionListener(new Consumer<List<Change>>() {
            @Override
            public void accept(List<Change> changes) {
                myChangesBrowser.setChanges(changes);
            }
        });

        myChangesBrowser = project.getApplication().getInstance(VcsChangesDialogFactory.class)
            .changesBrowser(project)
            .build(parentDisposable);
        myCommitListPanel.registerDiffAction(myChangesBrowser.getDiffAction());

        Splitter splitter = new Splitter(false, 0.7f);
        splitter.setHonorComponentsMinimumSize(false);
        splitter.setFirstComponent(myCommitListPanel);
        splitter.setSecondComponent((JComponent) TargetAWT.to(myChangesBrowser.getComponent()));
        add(splitter);
    }

    @Nonnull
    public JComponent getPreferredFocusComponent() {
        return myCommitListPanel.getPreferredFocusComponent();
    }

    public void setCommits(@Nonnull List<GitCommit> commits) {
        myCommitListPanel.setCommits(commits);
    }
}
