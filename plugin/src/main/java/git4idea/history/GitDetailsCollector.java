// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
import consulo.project.Project;
import consulo.util.collection.ArrayUtil;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.log.VcsCommitMetadata;
import consulo.versionControlSystem.log.VcsLogObjectsFactory;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.commands.Git;
import git4idea.commands.GitLineHandler;

import java.util.List;
import java.util.function.Consumer;

abstract class GitDetailsCollector<R extends GitLogRecord, C extends VcsCommitMetadata> {
    private static final Logger LOG = Logger.getInstance(GitDetailsCollector.class);

    protected final Project myProject;
    protected final VirtualFile myRoot;
    private final GitLogRecordBuilder<R> myRecordBuilder;

    protected GitDetailsCollector(Project project, VirtualFile root, GitLogRecordBuilder<R> recordBuilder) {
        myProject = project;
        myRoot = root;
        myRecordBuilder = recordBuilder;
    }

    public void readFullDetails(Consumer<? super C> commitConsumer,
                                GitCommitRequirements requirements,
                                boolean lowPriorityProcess,
                                String... parameters) throws VcsException {
        List<String> configParameters = requirements.configParameters();
        GitLineHandler handler = GitLogUtil.createGitHandler(myProject, myRoot, configParameters, lowPriorityProcess);
        readFullDetailsFromHandler(commitConsumer, handler, requirements, parameters);
    }

    public void readFullDetailsForHashes(List<String> hashes,
                                         GitCommitRequirements requirements,
                                         boolean lowPriorityProcess,
                                         Consumer<? super C> commitConsumer) throws VcsException {
        if (hashes.isEmpty()) {
            return;
        }
        GitLineHandler handler = GitLogUtil.createGitHandler(myProject, myRoot, requirements.configParameters(), lowPriorityProcess);
        GitLogUtil.sendHashesToStdin(hashes, handler);

        readFullDetailsFromHandler(commitConsumer, handler, requirements, GitLogUtil.getNoWalkParameter(myProject), GitLogUtil.STDIN);
    }

    private void readFullDetailsFromHandler(Consumer<? super C> commitConsumer,
                                            GitLineHandler handler,
                                            GitCommitRequirements requirements,
                                            String... parameters) throws VcsException {
        VcsLogObjectsFactory factory = GitLogUtil.getObjectsFactoryWithDisposeCheck(myProject);
        if (factory == null) {
            return;
        }

        String[] commandParameters = ArrayUtil.mergeArrays(ArrayUtil.toStringArray(requirements.commandParameters(myProject)), parameters);
        if (requirements.getDiffInMergeCommits() == GitCommitRequirements.DiffInMergeCommits.DIFF_TO_PARENTS) {
            Consumer<List<R>> consumer = records -> {
                R firstRecord = records.getFirst();
                String[] parents = firstRecord.getParentsHashes();

                if (parents.length == 0 || parents.length == records.size()) {
                    commitConsumer.accept(createCommit(records, factory, requirements));
                }
                else {
                    LOG.warn("Not enough records for commit " + firstRecord.getHash() + " " +
                        "expected " + parents.length + " records, but got " + records.size());
                }
            };

            GitLogRecordCollector<R> recordCollector = createRecordsCollector(consumer);
            readRecordsFromHandler(handler, recordCollector, commandParameters);
            recordCollector.finish();
        }
        else {
            Consumer<R> consumer = record -> commitConsumer.accept(createCommit(List.of(record), factory, requirements));

            readRecordsFromHandler(handler, consumer, commandParameters);
        }
    }

    private void readRecordsFromHandler(GitLineHandler handler, Consumer<R> converter, String... parameters) throws VcsException {
        GitLogParser<R> parser = new GitLogParser<>(myProject, myRecordBuilder, GitLogParser.NameStatus.STATUS, GitLogUtil.COMMIT_METADATA_OPTIONS);
        handler.setStdoutSuppressed(true);
        handler.addParameters(parameters);
        handler.addParameters(parser.getPretty(), "--encoding=UTF-8");
        handler.addParameters("--name-status");
        handler.endOptions();

        GitLogOutputSplitter<R> handlerListener = new GitLogOutputSplitter<>(handler, parser, converter);
        Git.getInstance().runCommandWithoutCollectingOutput(handler).throwOnError();
        handlerListener.reportErrors();
    }

    protected abstract GitLogRecordCollector<R> createRecordsCollector(Consumer<List<R>> consumer);

    protected abstract C createCommit(List<R> records, VcsLogObjectsFactory factory, GitCommitRequirements requirements);
}
