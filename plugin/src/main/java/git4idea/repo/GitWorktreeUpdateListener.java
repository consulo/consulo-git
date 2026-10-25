// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.repo;

import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitWorkingTree;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

final class GitWorktreeUpdateListener implements GitRepositoryUpdateListener {
    private final Project myCurrentProject;
    private final Consumer<GitRepository> myRepositoryUpdater;

    GitWorktreeUpdateListener(Project currentProject) {
        this(currentProject, repo -> repo.getWorkingTreeHolder().scheduleReload());
    }

    GitWorktreeUpdateListener(Project currentProject, Consumer<GitRepository> repositoryUpdater) {
        myCurrentProject = currentProject;
        myRepositoryUpdater = repositoryUpdater;
    }

    /**
     * Reacts on repository updates in other projects when the current project has a git repository with the worktree matching {@code root}.
     * In case of the match, updates the repository.
     * This mechanism provides faster feedback than reacting to VFS events.
     */
    @Override
    public void repositoryUpdated(Project updatedInProject, VirtualFile root) {
        if (myCurrentProject == updatedInProject) {
            return;
        }

        GitRepositoryManager repositoryManager = GitRepositoryManager.getInstance(myCurrentProject);
        if (repositoryManager == null) {
            return;
        }
        for (GitRepository repository : repositoryManager.getRepositories()) {
            Collection<GitWorkingTree> workingTrees = repository.getWorkingTreeHolder().getWorkingTrees();
            // Repository having no other (than self) working trees can be skipped
            if (workingTrees.size() <= 1) {
                continue;
            }

            boolean hasMatchingWorktree = workingTrees.stream().anyMatch(it -> Objects.equals(it.getPath().getVirtualFile(), root));
            if (hasMatchingWorktree) {
                myRepositoryUpdater.accept(repository);
            }
        }
    }
}
