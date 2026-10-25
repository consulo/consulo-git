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
import consulo.process.ProcessOutputTypes;
import consulo.project.Project;
import consulo.util.dataholder.Key;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.log.Hash;
import consulo.versionControlSystem.log.base.HashImpl;
import git4idea.GitTag;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandler;
import git4idea.commands.GitLineHandlerListener;
import git4idea.util.StringScanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GitRepositoryTagsHolderImpl implements GitRepositoryTagsHolder {
    private static final Logger LOG = Logger.getInstance(GitRepositoryTagsHolderImpl.class);

    private final GitRepository myRepository;
    private final ExecutorService myExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("GitRepositoryTagsHolder", 1);
    private final AtomicBoolean myReloadScheduled = new AtomicBoolean();

    private volatile GitRepositoryTagsState myState = NotLoadedGitRepositoryTagsState.INSTANCE;

    public GitRepositoryTagsHolderImpl(GitRepository repository) {
        myRepository = repository;
    }

    @Override
    public GitRepositoryTagsState getState() {
        return myState;
    }

    @Override
    public void scheduleReload() {
        if (myReloadScheduled.compareAndSet(false, true)) {
            myExecutor.execute(() -> {
                myReloadScheduled.set(false);
                if (Disposer.isDisposed(myRepository)) {
                    return;
                }
                try {
                    myState = loadTagsFromGit();
                }
                catch (VcsException e) {
                    LOG.warn("Failed to load tags from Git", e);
                    return;
                }
                Project project = myRepository.getProject();
                if (!project.isDisposed()) {
                    project.getMessageBus().syncPublisher(GitTagsHolderListener.class).tagsUpdated(myRepository);
                }
            });
        }
    }

    public GitRepositoryTagsState loadTagsFromGit() throws VcsException {
        GitLineHandler handler = new GitLineHandler(myRepository.getProject(), myRepository.getRoot(), GitCommand.FOR_EACH_REF);
        handler.addParameters("refs/tags/**");
        handler.addParameters("--no-color");
        handler.addParameters("--format=%(refname)\t%(*objectname)\t%(objectname)");

        TagsLineListener listener = new TagsLineListener();
        handler.addLineListener(listener);

        Git.getInstance().runCommand(handler).throwOnError();
        return listener.getTagsState();
    }

    private static final class TagsLineListener implements GitLineHandlerListener {
        private final Map<GitTag, Hash> myTagsToHashes = new HashMap<>();
        private final Map<Hash, List<GitTag>> myHashesToTags = new HashMap<>();
        private int myBadLineReported = 0;

        @Override
        public void onLineAvailable(String line, Key outputType) {
            try {
                if (ProcessOutputTypes.STDOUT.equals(outputType)) {
                    StringScanner scanner = new StringScanner(line);
                    String tagName = scanner.tabToken();
                    if (tagName == null) {
                        return;
                    }
                    String dereferencedHash = scanner.tabToken();
                    if (dereferencedHash == null) {
                        return;
                    }
                    String tagHash = scanner.line();
                    if (tagHash == null) {
                        return;
                    }

                    // Use dereferenced hash if available (for annotated tags), otherwise use tag hash (for lightweight tags)
                    String commitHash = dereferencedHash.isBlank() ? tagHash : dereferencedHash;
                    addTag(tagName, commitHash);
                }
            }
            catch (RuntimeException e) {
                myBadLineReported++;
                if (myBadLineReported < 5) {
                    LOG.warn("Unexpected output: " + line, e);
                }
            }
        }

        private void addTag(String tagName, String hash) {
            GitTag gitTag = new GitTag(tagName);
            Hash hashObj = HashImpl.build(hash);
            myHashesToTags.computeIfAbsent(hashObj, key -> new ArrayList<>()).add(gitTag);
            myTagsToHashes.put(gitTag, hashObj);
        }

        GitRepositoryTagsState getTagsState() {
            return new LoadedGitRepositoryTagsState(myTagsToHashes, myHashesToTags);
        }

        @Override
        public void processTerminated(int exitCode) {
        }

        @Override
        public void startFailed(Throwable exception) {
        }
    }
}
