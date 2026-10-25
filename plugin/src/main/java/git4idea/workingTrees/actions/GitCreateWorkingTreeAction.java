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

import consulo.annotation.component.ActionImpl;
import consulo.git.icon.GitIconGroup;
import consulo.git.localize.GitLocalize;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.ex.action.Presentation;
import consulo.ui.image.Image;
import git4idea.GitBranch;
import git4idea.GitReference;
import git4idea.GitTag;
import git4idea.repo.GitRepository;
import git4idea.workingTrees.GitCreateWorkingTreeService;
import git4idea.workingTrees.GitWorkingTreesService;
import git4idea.workingTrees.GitWorktreeSupportStatus;
import git4idea.workingTrees.SingleRepositoryGitWorktreeSupportStatus;
import git4idea.workingTrees.UnsupportedGitWorktreeSupportStatus;
import git4idea.workingTrees.ui.GitWorkingTreesContentFactory;
import git4idea.workingTrees.ui.actions.GitWorkingTreeTabActionsDataKeys;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Creates a new worktree. The registered instance takes its repository from the Worktrees tab (or the only
 * worktree-capable repository); the branch popup creates instances bound to the repository and the ref of its row.
 */
@ActionImpl(id = "Git.CreateNewWorkingTree")
public class GitCreateWorkingTreeAction extends DumbAwareAction implements AnActionWithSyncUpdate {
    private final @Nullable GitRepository myRepository;
    private final @Nullable GitReference myRef;
    private final boolean myUseCurrentBranch;

    @Inject
    public GitCreateWorkingTreeAction() {
        this(null, null, false);
    }

    public GitCreateWorkingTreeAction(@Nullable GitRepository repository, @Nullable GitReference ref, boolean useCurrentBranch) {
        super(GitLocalize.actionGitCreatenewworkingtreeText(), GitLocalize.actionGitCreatenewworkingtreeDescription(), null);
        myRepository = repository;
        myRef = ref;
        myUseCurrentBranch = useCurrentBranch;
        if (repository != null) {
            updatePresentation(getTemplatePresentation(), GitWorkingTreesService.getWorktreeSupportStatus(repository.getProject()), repository, null);
        }
    }

    @Override
    public void update(AnActionEvent e) {
        GitWorktreeSupportStatus status = GitWorkingTreesService.getWorktreeSupportStatus(e.getData(Project.KEY));
        updatePresentation(e.getPresentation(), status, resolveContextRepository(e, status), e.getPlace());
    }

    private void updatePresentation(Presentation presentation,
                                    GitWorktreeSupportStatus status,
                                    @Nullable GitRepository contextRepository,
                                    @Nullable String place) {
        if (status instanceof UnsupportedGitWorktreeSupportStatus) {
            presentation.setEnabledAndVisible(false);
            return;
        }
        if (myRef != null && !(myRef instanceof GitBranch) && !(myRef instanceof GitTag)) {
            presentation.setEnabledAndVisible(false);
            return;
        }

        presentation.setEnabledAndVisible(true);
        presentation.setIcon(computeIcon(place));
        GitReference localBranchFromContext = getRefFromContext(contextRepository);
        if (localBranchFromContext == null) {
            presentation.setText(GitLocalize.actionGitCreatenewworkingtreeText());
            presentation.setDescription(GitLocalize.actionGitCreatenewworkingtreeDescription());
        }
        else {
            String refName = localBranchFromContext.getName();
            presentation.setText(GitLocalize.actionGitCreatenewworkingtreeFromBranchText(refName));
            presentation.setDescription(localBranchFromContext instanceof GitTag
                ? GitLocalize.actionGitCreatenewworkingtreeFromTagDescription(refName)
                : GitLocalize.actionGitCreatenewworkingtreeFromBranchDescription(refName));
        }
    }

    private @Nullable Image computeIcon(@Nullable String place) {
        if (GitWorkingTreesContentFactory.GIT_WORKING_TREE_TOOLWINDOW_TAB_TOOLBAR.equals(place)) {
            return PlatformIconGroup.generalAdd();
        }
        if (myUseCurrentBranch) {
            return GitIconGroup.worktree();
        }
        return null;
    }

    @RequiredUIAccess
    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        if (project == null) {
            return;
        }
        GitWorktreeSupportStatus status = GitWorkingTreesService.getWorktreeSupportStatus(project);
        GitRepository contextRepository = resolveContextRepository(e, status);
        GitReference refFromContext = getRefFromContext(contextRepository);

        // When creating from a specific ref, the ref belongs to a specific repository, so no switching is offered.
        // Otherwise the dialog lets the user pick/switch the target repository among all worktree-capable ones.
        List<GitRepository> candidates = refFromContext != null && contextRepository != null
            ? List.of(contextRepository)
            : GitWorkingTreesService.findWorktreeCapableRepositories(project);
        GitRepository initialRepository = contextRepository != null ? contextRepository : candidates.isEmpty() ? null : candidates.getFirst();
        if (initialRepository == null) {
            return;
        }

        GitCreateWorkingTreeService.getInstance()
            .collectDataAndCreateWorkingTree(initialRepository, refFromContext, candidates, true, null);
    }

    private @Nullable GitRepository resolveContextRepository(AnActionEvent e, GitWorktreeSupportStatus status) {
        if (myRepository != null) {
            return myRepository;
        }
        GitRepository fromTab = GitWorkingTreeTabActionsDataKeys.getCurrentRepository(e);
        if (fromTab != null) {
            return fromTab;
        }
        return status instanceof SingleRepositoryGitWorktreeSupportStatus single ? single.repository() : null;
    }

    private @Nullable GitReference getRefFromContext(@Nullable GitRepository repository) {
        GitReference ref;
        if (myRef != null) {
            ref = myRef;
        }
        else if (myUseCurrentBranch && repository != null) {
            ref = repository.getCurrentBranch();
        }
        else {
            ref = null;
        }
        return ref instanceof GitBranch || ref instanceof GitTag ? ref : null;
    }
}
