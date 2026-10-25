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
package git4idea.workingTrees.actions;

import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.DumbAwareAction;
import git4idea.GitReference;
import git4idea.GitWorkingTree;
import git4idea.repo.GitRepository;
import git4idea.workingTrees.GitWorkingTreesService;
import git4idea.workingTrees.GitWorkingTreesUtil;

import java.util.ArrayList;
import java.util.List;

public class GitOpenExistingWorkingTreeForLocalBranchAction extends DumbAwareAction implements AnActionWithSyncUpdate {
    private final List<GitRepository> myRepositories;
    private final GitReference myReference;

    public GitOpenExistingWorkingTreeForLocalBranchAction(List<GitRepository> repositories, GitReference reference) {
        super(GitLocalize.actionOpenWorktreeForABranchText());
        myRepositories = repositories;
        myReference = reference;
        getTemplatePresentation().setEnabledAndVisible(isEnabledForRef(reference, repositories));
    }

    @Override
    public void update(AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(isEnabledForRef(myReference, myRepositories));
    }

    private static boolean isEnabledForRef(GitReference ref, List<GitRepository> repositories) {
        return !findCheckedOutWorkingTrees(ref, repositories, true).isEmpty();
    }

    private static List<GitWorkingTree> findCheckedOutWorkingTrees(GitReference ref, List<GitRepository> repositories, boolean skipCurrentWorkingTree) {
        List<GitWorkingTree> result = new ArrayList<>();
        for (GitRepository repository : repositories) {
            result.addAll(GitWorkingTreesUtil.findCheckedOutWorkingTrees(ref, repository.getWorkingTreeHolder().getWorkingTrees(), skipCurrentWorkingTree));
        }
        return result;
    }

    @RequiredUIAccess
    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        if (project == null) {
            return;
        }
        List<GitWorkingTree> workingTrees = findCheckedOutWorkingTrees(myReference, myRepositories, false);
        GitChooseWorkingTreeDialog.chooseAmong(project, workingTrees).whenCompleteAsync((chosen, error) -> {
            if (chosen != null) {
                GitWorkingTreesService.getInstance(project).openWorkingTreeProject(chosen);
            }
        }, project.getUIAccess());
    }
}
