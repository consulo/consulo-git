// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.checkin;

import consulo.git.localize.GitLocalize;
import consulo.process.ProcessOutputType;
import consulo.project.Project;
import consulo.util.dataholder.Key;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import git4idea.commands.GitLineHandlerAdapter;
import git4idea.repo.GitRepository;

import java.io.File;
import java.util.List;

public class GitRepositoryCommitter {
    private static final List<String> EMPTY_COMMIT_PATTERNS = List.of(
        "No changes",
        "no changes added to commit",
        "nothing added to commit",
        "nothing to commit"
    );

    private final GitRepository myRepository;

    public GitRepositoryCommitter(GitRepository repository) {
        myRepository = repository;
    }

    public GitRepository getRepository() {
        return myRepository;
    }

    public Project getProject() {
        return myRepository.getProject();
    }

    public VirtualFile getRoot() {
        return myRepository.getRoot();
    }

    public void commitStaged(String commitMessage) throws VcsException {
        GitCheckinEnvironment.runWithMessageFile(getProject(), getRoot(), commitMessage, this::performCommit);
    }

    @Deprecated
    public void commitStaged(File messageFile) throws VcsException {
        performCommit(messageFile);
    }

    private void performCommit(File messageFile) throws VcsException {
        boolean[] emptyCommitDetected = new boolean[1];
        GitLineHandler handler = new GitLineHandler(getProject(), getRoot(), GitCommand.COMMIT);
        handler.setStdoutSuppressed(false);
        handler.addLineListener(new GitLineHandlerAdapter() {
            @Override
            public void onLineAvailable(String line, Key outputType) {
                if (ProcessOutputType.isStdout(outputType)
                    && EMPTY_COMMIT_PATTERNS.stream().anyMatch(it -> line.regionMatches(true, 0, it, 0, it.length()))) {
                    emptyCommitDetected[0] = true;
                }
            }
        });

        handler.addParameters("-F");
        handler.addAbsoluteFile(messageFile);
        handler.endOptions();

        GitCommandResult command = Git.getInstance().runCommand(handler);
        try {
            command.throwOnError();
        }
        catch (VcsException e) {
            if (emptyCommitDetected[0]) {
                throw new VcsException(GitLocalize.gitCommitNothingToCommitErrorMessage());
            }
            throw e;
        }
    }
}
