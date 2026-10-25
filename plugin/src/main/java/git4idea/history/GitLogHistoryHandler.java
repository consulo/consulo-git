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
 */package git4idea.history;

import consulo.logging.Logger;
import consulo.process.ProcessOutputTypes;
import consulo.project.Project;
import consulo.util.dataholder.Key;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.log.base.VcsFileStatusInfo;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandler;
import git4idea.commands.GitLineHandlerAdapter;
import git4idea.commands.GitLineHandlerListener;
import org.jspecify.annotations.Nullable;

/**
 * The rename lookup of the git log file history handler.
 */
public class GitLogHistoryHandler {
    private static final Logger LOG = Logger.getInstance(GitLogHistoryHandler.class);

    static @Nullable VcsFileStatusInfo getRename(Project project,
                                                 VirtualFile root,
                                                 String beforeHash,
                                                 String afterHash,
                                                 FilePath filePath) throws VcsException {
        GitLineHandler h = new GitLineHandler(project, root, GitCommand.DIFF);
        h.setStdoutSuppressed(true);
        h.addParameters("-M", "--diff-filter=R", "--name-status", "--encoding=UTF-8", "--follow", beforeHash + ".." + afterHash);
        h.endOptions();
        h.addRelativePaths(filePath);

        RenamesCollector renamesCollector = new RenamesCollector(h.printableCommandLine());
        h.addLineListener(renamesCollector.getLineListener());
        Git.getInstance().runCommandWithoutCollectingOutput(h).throwOnError();

        return renamesCollector.getSingleRename();
    }

    private static class RenamesCollector extends DefaultGitLogFullRecordBuilder {
        private final String myCommandLine;
        private boolean myUnexpectedStatusReported;

        RenamesCollector(String commandLine) {
            myCommandLine = commandLine;
        }

        @Override
        public void addPath(Change.Type type, String firstPath, @Nullable String secondPath) {
            if (type == Change.Type.MOVED) {
                super.addPath(type, firstPath, secondPath);
            }
            else if (!myUnexpectedStatusReported) {
                myUnexpectedStatusReported = true;
                LOG.error("Unexpected change " + type + " " + firstPath + " " + secondPath + " in the output of [" + myCommandLine + "]");
            }
        }

        GitLineHandlerListener getLineListener() {
            GitLogParser.PathsParser<GitLogFullRecord> parser = new GitLogParser.PathsParser<>(GitLogParser.NameStatus.STATUS, this);
            return new GitLineHandlerAdapter() {
                @Override
                public void onLineAvailable(String line, Key outputType) {
                    if (ProcessOutputTypes.STDOUT.equals(outputType)) {
                        parser.parseLine(line);
                    }
                }
            };
        }

        @Nullable VcsFileStatusInfo getSingleRename() {
            if (getStatuses().size() > 1) {
                LOG.error("Unexpected multiple renames found. Command [" + myCommandLine + "].\n" +
                    "Output [" + getStatuses() + "].");
            }

            VcsFileStatusInfo info = getStatuses().size() == 1 ? getStatuses().getFirst() : null;
            if (info != null && info.getSecondPath() != null) {
                return info;
            }
            return null;
        }
    }
}
