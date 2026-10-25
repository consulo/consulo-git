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
package git4idea.repo;

import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.disposer.Disposer;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.versionControlSystem.VcsException;
import git4idea.GitWorkingTree;
import git4idea.commands.Git;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

final class GitWorkingTreeHolderImpl implements GitWorkingTreeHolder {
    private static final Logger LOG = Logger.getInstance(GitWorkingTreeHolderImpl.class);

    private final GitRepository myRepository;
    private final ExecutorService myExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("GitWorkingTreeHolderImpl", 1);
    private final AtomicBoolean myReloadScheduled = new AtomicBoolean();
    private final Object myUpdateLock = new Object();

    private volatile Collection<GitWorkingTree> myState = List.of();

    GitWorkingTreeHolderImpl(GitRepository repository) {
        myRepository = repository;
    }

    @Override
    public Collection<GitWorkingTree> getWorkingTrees() {
        return myState;
    }

    @Override
    public void scheduleReload() {
        if (myReloadScheduled.compareAndSet(false, true)) {
            myExecutor.execute(() -> {
                myReloadScheduled.set(false);
                updateState();
            });
        }
    }

    void updateState() {
        synchronized (myUpdateLock) {
            if (Disposer.isDisposed(myRepository)) {
                return;
            }
            myState = readWorkingTreesFromGit();
            Project project = myRepository.getProject();
            if (!project.isDisposed()) {
                project.getMessageBus().syncPublisher(GitWorkingTreesListener.class).workingTreesLoaded(myRepository);
            }
        }
    }

    private Collection<GitWorkingTree> readWorkingTreesFromGit() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Reloading working trees for " + myRepository.getRoot());
        }

        List<GitWorkingTree> trees;
        try {
            trees = Git.getInstance().listWorktrees(myRepository);
        }
        catch (VcsException e) {
            LOG.info("Failed to list worktrees for " + myRepository.getRoot(), e);
            return List.of();
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Get working trees for " + myRepository.getRoot() + ": " + trees);
        }

        return trees;
    }
}
