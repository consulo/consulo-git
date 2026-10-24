// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.branch;

import consulo.annotation.component.ExtensionImpl;
import consulo.application.progress.ProgressIndicator;
import consulo.project.Project;
import git4idea.fetch.GitFetchHandler;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ExtensionImpl
public final class IncomingOutgoingRefreshFetchHandler implements GitFetchHandler {
    @Override
    public void doAfterSuccessfulFetch(Project project, Map<GitRepository, List<GitRemote>> fetches, ProgressIndicator indicator) {
        Set<GitRepository> updatedRepos = fetches.keySet();
        if (updatedRepos.isEmpty()) {
            return;
        }

        GitBranchIncomingOutgoingManager manager = GitBranchIncomingOutgoingManager.getInstance(project);
        if (GitRepositoryManager.getInstance(project).getRepositories().size() == 1) {
            manager.myLastFetchTime.set(Instant.now());
        }
        manager.markDirty(updatedRepos, updatedRepos, false);
    }
}
