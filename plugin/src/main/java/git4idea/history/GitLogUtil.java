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
 */
package git4idea.history;

import consulo.util.collection.Sets;
import consulo.application.ReadAction;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import consulo.util.collection.ArrayUtil;
import consulo.ide.impl.idea.util.CollectConsumer;
import consulo.util.collection.SmartList;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.log.Hash;
import consulo.versionControlSystem.log.TimedVcsCommit;
import consulo.versionControlSystem.log.VcsCommitMetadata;
import consulo.versionControlSystem.log.VcsLogObjectsFactory;
import consulo.versionControlSystem.log.VcsLogProvider;
import consulo.versionControlSystem.log.VcsRef;
import consulo.versionControlSystem.log.VcsRefType;
import consulo.versionControlSystem.log.VcsUser;
import consulo.versionControlSystem.log.base.HashImpl;
import consulo.versionControlSystem.log.LogDataImpl;
import git4idea.GitCommit;
import git4idea.GitUtil;
import git4idea.branch.GitBranchUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitHandler;
import git4idea.commands.GitHandlerInputProcessorUtil;
import git4idea.commands.GitLineHandler;
import git4idea.config.GitVersionSpecialty;
import git4idea.log.GitLogProvider;
import git4idea.log.GitRefManager;

import org.jspecify.annotations.Nullable;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static git4idea.history.GitLogParser.GitLogOption.AUTHOR_EMAIL;
import static git4idea.history.GitLogParser.GitLogOption.AUTHOR_NAME;
import static git4idea.history.GitLogParser.GitLogOption.AUTHOR_TIME;
import static git4idea.history.GitLogParser.GitLogOption.BODY;
import static git4idea.history.GitLogParser.GitLogOption.COMMITTER_EMAIL;
import static git4idea.history.GitLogParser.GitLogOption.COMMITTER_NAME;
import static git4idea.history.GitLogParser.GitLogOption.COMMIT_TIME;
import static git4idea.history.GitLogParser.GitLogOption.HASH;
import static git4idea.history.GitLogParser.GitLogOption.PARENTS;
import static git4idea.history.GitLogParser.GitLogOption.RAW_BODY;
import static git4idea.history.GitLogParser.GitLogOption.REF_NAMES;
import static git4idea.history.GitLogParser.GitLogOption.SUBJECT;

public final class GitLogUtil {
  private static final Logger LOG = Logger.getInstance(GitLogUtil.class);
  public static final String GRAFTED = "grafted";
  public static final String REPLACED = "replaced";
  /**
   * A parameter to {@code git log} which is equivalent to {@code --all}, but doesn't show the stuff from index or stash.
   */
  public static final List<String> LOG_ALL = List.of(GitUtil.HEAD, "--branches", "--remotes", "--tags");
  public static final String STDIN = "--stdin";
  public static final GitLogParser.GitLogOption[] COMMIT_METADATA_OPTIONS = {
    HASH, PARENTS,
    COMMIT_TIME, COMMITTER_NAME, COMMITTER_EMAIL,
    AUTHOR_NAME, AUTHOR_TIME, AUTHOR_EMAIL,
    SUBJECT, BODY, RAW_BODY
  };

  public static void readTimedCommits(Project project,
                                      VirtualFile root,
                                      List<String> parameters,
                                      @Nullable Consumer<? super VcsUser> userConsumer,
                                      @Nullable Consumer<? super VcsRef> refConsumer,
                                      Consumer<? super TimedVcsCommit> commitConsumer) throws VcsException {
    readTimedCommits(project, root, Collections.emptyList(), parameters, userConsumer, refConsumer, commitConsumer);
  }

  public static void readTimedCommits(Project project,
                                      VirtualFile root,
                                      List<String> configParameters,
                                      List<String> parameters,
                                      @Nullable Consumer<? super VcsUser> userConsumer,
                                      @Nullable Consumer<? super VcsRef> refConsumer,
                                      Consumer<? super TimedVcsCommit> commitConsumer) throws VcsException {
    readTimedCommits(project, root, configParameters, parameters, Collections.emptyList(), userConsumer, refConsumer, commitConsumer);
  }

  public static void readTimedCommits(Project project,
                                      VirtualFile root,
                                      List<String> configParameters,
                                      List<String> parameters,
                                      List<FilePath> filePaths,
                                      @Nullable Consumer<? super VcsUser> userConsumer,
                                      @Nullable Consumer<? super VcsRef> refConsumer,
                                      Consumer<? super TimedVcsCommit> commitConsumer) throws VcsException {
    VcsLogObjectsFactory factory = getObjectsFactoryWithDisposeCheck(project);
    if (factory == null) {
      return;
    }

    GitLineHandler handler = createGitHandler(project, root, configParameters, false);
    List<GitLogParser.GitLogOption> options = new ArrayList<>(Arrays.asList(HASH, PARENTS, COMMIT_TIME));
    if (userConsumer != null) {
      options.add(AUTHOR_NAME);
      options.add(AUTHOR_EMAIL);
    }
    if (refConsumer != null) {
      options.add(REF_NAMES);
    }

    GitLogParser<GitLogRecord> parser = GitLogParser.createDefaultParser(project, options.toArray(new GitLogParser.GitLogOption[0]));
    handler.setStdoutSuppressed(true);
    handler.addParameters(parser.getPretty(), "--encoding=UTF-8");
    handler.addParameters("--decorate=full");
    if (parameters.contains("--")) {
      int index = parameters.indexOf("--");
      handler.addParameters(parameters.subList(0, index));
      handler.endOptions();
      handler.addParameters(parameters.subList(index + 1, parameters.size()));
    } else {
      handler.addParameters(parameters);
      handler.endOptions();
    }
    handler.addRelativePaths(filePaths);

    GitLogOutputSplitter<GitLogRecord> handlerListener = new GitLogOutputSplitter<>(handler, parser, record -> {
      Hash hash = HashImpl.build(record.getHash());
      List<Hash> parents = ContainerUtil.map(record.getParentsHashes(), factory::createHash);
      commitConsumer.accept(factory.createTimedCommit(hash, parents, record.getCommitTime()));

      if (refConsumer != null) {
        for (VcsRef ref : parseRefs(record.getRefs(), hash, factory, root)) {
          refConsumer.accept(ref);
        }
      }

      if (userConsumer != null) userConsumer.accept(factory.createUser(record.getAuthorName(), record.getAuthorEmail()));
    });
    Git.getInstance().runCommandWithoutCollectingOutput(handler).throwOnError();
    handlerListener.reportErrors();
  }

  public static List<? extends VcsCommitMetadata> collectMetadata(Project project, VirtualFile root,
                                                                           List<String> hashes)
    throws VcsException {
    CollectConsumer<VcsCommitMetadata> collectConsumer = new CollectConsumer<>();
    collectMetadata(project, root, hashes, collectConsumer);
    return new ArrayList<>(collectConsumer.getResult());
  }

  public static @Nullable VcsCommitMetadata collectMetadataForCommit(Project project,
                                                                     VirtualFile root,
                                                                     String commit)
    throws VcsException {
    return ContainerUtil.getOnlyItem(collectMetadata(project, root, Collections.singletonList(commit)));
  }

  public static void collectMetadata(Project project, VirtualFile root,
                                     List<String> hashes, Consumer<? super VcsCommitMetadata> consumer)
    throws VcsException {
    if (hashes.isEmpty()) return;
    VcsLogObjectsFactory factory = getObjectsFactoryWithDisposeCheck(project);
    if (factory == null) return;

    GitLineHandler h = createGitHandler(project, root);
    GitLogParser<GitLogRecord> parser = GitLogParser.createDefaultParser(project, COMMIT_METADATA_OPTIONS);
    h.setSilent(true);
    // git show can show either -p, or --name-status, or --name-only, but we need nothing, just details => using git log --no-walk
    h.addParameters(getNoWalkParameter(project));
    h.addParameters(parser.getPretty(), "--encoding=UTF-8");
    h.addParameters(STDIN);
    h.endOptions();

    sendHashesToStdin(hashes, h);

    GitLogOutputSplitter<GitLogRecord> outputHandler = new GitLogOutputSplitter<>(h, parser, (record) -> {
      List<Hash> parents = new SmartList<>();
      for (String parent : record.getParentsHashes()) {
        parents.add(HashImpl.build(parent));
      }
      record.setUsedHandler(h);
      consumer.accept(factory.createCommitMetadata(HashImpl.build(record.getHash()), parents, record.getCommitTime(), root,
                                                   record.getSubject(), record.getAuthorName(), record.getAuthorEmail(),
                                                   record.getFullMessage(),
                                                   record.getCommitterName(), record.getCommitterEmail(), record.getAuthorTimeStamp()));
    });

    Git.getInstance().runCommandWithoutCollectingOutput(h).throwOnError();
    outputHandler.reportErrors();
  }

  public static VcsLogProvider.DetailedLogData collectMetadata(Project project,
                                                                        VirtualFile root,
                                                                        String... params) throws VcsException {
    return collectMetadata(project, root, new GitLogCommandParameters(Collections.emptyList(), Arrays.asList(params)));
  }

  public static VcsLogProvider.DetailedLogData collectMetadata(Project project,
                                                                        VirtualFile root,
                                                                        GitLogCommandParameters parameters) throws VcsException {
    VcsLogObjectsFactory factory = getObjectsFactoryWithDisposeCheck(project);
    if (factory == null) {
      return LogDataImpl.empty();
    }

    Set<VcsRef> refs = Sets.newHashSet(GitLogProvider.DONT_CONSIDER_SHA);
    List<VcsCommitMetadata> commits = new ArrayList<>();
    Consumer<GitLogRecord> recordConsumer = record -> {
      VcsCommitMetadata commit = createMetadata(root, record, factory);
      commits.add(commit);
      Collection<VcsRef> refsInRecord = parseRefs(record.getRefs(), commit.getId(), factory, root);
      for (VcsRef ref : refsInRecord) {
        if (!refs.add(ref)) {
          VcsRef otherRef = ContainerUtil.find(refs, r -> GitLogProvider.DONT_CONSIDER_SHA.equals(r, ref));
          LOG.error("Adding duplicate element " + ref + " to the set containing " + otherRef);
        }
      }
    };

    try {
      GitLineHandler handler = createGitHandler(project, root, parameters.configParameters(), false);
      GitLogParser.GitLogOption[] options = ArrayUtil.append(COMMIT_METADATA_OPTIONS, REF_NAMES);
      GitLogParser<GitLogRecord> parser = GitLogParser.createDefaultParser(project, options);
      handler.setStdoutSuppressed(true);
      handler.addParameters(parameters.filterParameters());
      handler.addParameters(parser.getPretty(), "--encoding=UTF-8");
      handler.addParameters("--decorate=full");
      handler.endOptions();

      GitLogOutputSplitter<GitLogRecord> handlerListener = new GitLogOutputSplitter<>(handler, parser, recordConsumer);
      Git.getInstance().runCommandWithoutCollectingOutput(handler).throwOnError();
      handlerListener.reportErrors();
    }
    catch (VcsException e) {
      if (commits.isEmpty()) {
        throw e;
      }
      LOG.warn("Error during loading details, returning partially loaded commits\n", e);
    }

    return new LogDataImpl(refs, commits);
  }

  public static void readFullDetails(Project project,
                                     VirtualFile root,
                                     Consumer<? super GitCommit> commitConsumer,
                                     String... parameters) throws VcsException {
    new GitFullDetailsCollector(project, root, new InternedGitLogRecordBuilder())
      .readFullDetails(commitConsumer, GitCommitRequirements.DEFAULT, false, parameters);
  }

  public static void readFullDetailsForHashes(Project project,
                                              VirtualFile root,
                                              List<String> hashes,
                                              GitCommitRequirements requirements,
                                              Consumer<? super GitCommit> commitConsumer) throws VcsException {
    if (hashes.isEmpty()) {
      return;
    }
    new GitFullDetailsCollector(project, root, new InternedGitLogRecordBuilder())
      .readFullDetailsForHashes(hashes, requirements, false, commitConsumer);
  }

  private static Collection<VcsRef> parseRefs(Collection<String> refs,
                                                       Hash hash,
                                                       VcsLogObjectsFactory factory,
                                                       VirtualFile root) {
    return ContainerUtil.mapNotNull(refs, refName -> {
      if (refName.equals(GRAFTED) || refName.equals(REPLACED)) return null;
      VcsRefType type = GitRefManager.getRefType(refName);
      refName = GitBranchUtil.stripRefsPrefix(refName);
      return refName.equals(GitUtil.ORIGIN_HEAD) ? null : factory.createRef(hash, refName, type, root);
    });
  }

  public static @Nullable VcsLogObjectsFactory getObjectsFactoryWithDisposeCheck(Project project) {
    return ReadAction.compute(() -> {
      if (!project.isDisposed()) {
        return project.getService(VcsLogObjectsFactory.class);
      }
      return null;
    });
  }

  public static VcsCommitMetadata createMetadata(VirtualFile root, GitLogRecord record,
                                                   VcsLogObjectsFactory factory) {
    List<Hash> parents = ContainerUtil.map(record.getParentsHashes(), factory::createHash);
    return factory.createCommitMetadata(factory.createHash(record.getHash()), parents, record.getCommitTime(), root, record.getSubject(),
                                        record.getAuthorName(), record.getAuthorEmail(), record.getFullMessage(),
                                        record.getCommitterName(), record.getCommitterEmail(), record.getAuthorTimeStamp());
  }

  /**
   * Sends hashes to process's stdin (without closing it on Windows).
   *
   * @see GitHandlerInputProcessorUtil#writeLines(Collection, String, Charset, boolean)
   */
  public static void sendHashesToStdin(Collection<String> hashes, GitHandler handler) {
    handler.setInputProcessor(GitHandlerInputProcessorUtil.writeLines(hashes,
                                                                      "\n",
                                                                      handler.getCharset(),
                                                                      true));
  }

  public static String getNoWalkParameter(Project project) {
    return GitVersionSpecialty.NO_WALK_UNSORTED.existsIn(project) ? "--no-walk=unsorted" : "--no-walk";
  }

  public static GitLineHandler createGitHandler(Project project, VirtualFile root) {
    return createGitHandler(project, root, Collections.emptyList(), false);
  }

  static GitLineHandler createGitHandler(Project project,
                                                  VirtualFile root,
                                                  List<String> configParameters,
                                                  boolean lowPriorityProcess) {
    GitLineHandler handler = new GitLineHandler(project, root, GitCommand.LOG, configParameters);
    if (lowPriorityProcess) handler.withLowPriority();
    return handler;
  }

  public static long parseTime(String timeString) {
    return Long.parseLong(timeString.trim()) * 1000;
  }
}
