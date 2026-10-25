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

import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.FilePath;
import consulo.virtualFileSystem.status.FileStatus;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.diff.ItemLatestState;
import consulo.versionControlSystem.history.VcsRevisionDescription;
import consulo.versionControlSystem.history.VcsRevisionDescriptionImpl;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.virtualFileSystem.VirtualFile;
import consulo.util.collection.ArrayUtil;
import java.util.function.Consumer;
import consulo.versionControlSystem.log.TimedVcsCommit;
import consulo.versionControlSystem.log.VcsCommitMetadata;
import consulo.versionControlSystem.log.VcsLogObjectsFactory;
import consulo.versionControlSystem.util.VcsUtil;
import git4idea.GitBranch;
import git4idea.GitCommit;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.branch.GitRebaseParams;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRepository;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static git4idea.history.GitLogParser.GitLogOption.AUTHOR_NAME;
import static git4idea.history.GitLogParser.GitLogOption.BODY;
import static git4idea.history.GitLogParser.GitLogOption.COMMITTER_NAME;
import static git4idea.history.GitLogParser.GitLogOption.COMMIT_TIME;
import static git4idea.history.GitLogParser.GitLogOption.HASH;
import static git4idea.history.GitLogParser.GitLogOption.PARENTS;
import static git4idea.history.GitLogParser.GitLogOption.RAW_BODY;
import static git4idea.history.GitLogParser.GitLogOption.SUBJECT;

/**
 * A collection of methods for retrieving history information from native Git.
 */
public final class GitHistoryUtils {
  private static final Logger LOG = Logger.getInstance(GitHistoryUtils.class);

  private GitHistoryUtils() {
  }

  /**
   * Load commit information in a form of {@link GitCommit} (containing commit details and changes to commit parents)
   * in the repository using `git log` command.
   *
   * @param project        context project
   * @param root           git repository root
   * @param commitConsumer consumer for commits
   * @param parameters     additional parameters for `git log` command
   * @throws VcsException if there is a problem with running git
   */
  @SuppressWarnings("unused") // used externally
  public static void loadDetails(Project project,
                                 VirtualFile root,
                                 Consumer<? super GitCommit> commitConsumer,
                                 String... parameters) throws VcsException {
    GitLogUtil.readFullDetails(project, root, commitConsumer, parameters);
  }

  /**
   * Load commit information in a form of {@link TimedVcsCommit} (containing hash, parents and commit time)
   * in the repository using `git log` command.
   *
   * @param project        context project
   * @param root           git repository root
   * @param commitConsumer consumer for commits
   * @param parameters     additional parameters for `git log` command
   * @throws VcsException if there is a problem with running git
   */
  public static void loadTimedCommits(Project project,
                                      VirtualFile root,
                                      Consumer<? super TimedVcsCommit> commitConsumer,
                                      String... parameters) throws VcsException {
    GitLogUtil.readTimedCommits(project, root, Arrays.asList(parameters), null, null, commitConsumer);
  }

  /**
   * Collect commit information in a form of {@link TimedVcsCommit} (containing hash, parents and commit time)
   * in the repository using `git log` command.
   *
   * @param project    context project
   * @param root       git repository root
   * @param parameters additional parameters for `git log` command
   * @throws VcsException if there is a problem with running git
   * @see GitHistoryUtils#formHashParameters(Project, Collection)
   */
  public static List<? extends TimedVcsCommit> collectTimedCommits(Project project,
                                                                            VirtualFile root,
                                                                            String... parameters) throws VcsException {
    List<TimedVcsCommit> commits = new ArrayList<>();
    loadTimedCommits(project, root, commits::add, parameters);
    return commits;
  }

  /**
   * Collect commit information in a form of {@link VcsCommitMetadata} (containing commit details, but no changes)
   * for the specified hashes or references.
   * <br/>
   * Note: this method accepts a list of <em>hashes or references</em>, not arbitrary parameters.
   *
   * @param project context project
   * @param root    git repository root
   * @param hashes  hashes or references
   * @return a list of {@link VcsCommitMetadata} (one for each specified hash or reference) or null if something went wrong
   * @throws VcsException if there is a problem with running git
   */
  public static @Nullable List<? extends VcsCommitMetadata> collectCommitsMetadata(Project project,
                                                                                   VirtualFile root,
                                                                                   String... hashes)
    throws VcsException {
    List<? extends VcsCommitMetadata> result = GitLogUtil.collectMetadata(project, root, Arrays.asList(hashes));
    if (result.size() != hashes.length) return null;
    return result;
  }

  /**
   * Collect commit information in a form of {@link GitCommit} (containing commit details and changes to commit parents)
   * in the repository using `git log` command.
   * If changes for commits are not needed, it is recommended to use other methods,
   * such as {@link GitHistoryUtils#collectCommitsMetadata(Project, VirtualFile, String...)}
   * or {@link GitHistoryUtils#collectTimedCommits(Project, VirtualFile, String...)}.
   * <br/>
   * Warning: this is method is efficient by speed, but don't query too much, because the whole log output is retrieved at once,
   * and it can occupy too much memory. The estimate is ~600Kb for 1000 commits.
   *
   * @param project    context project
   * @param root       git repository root
   * @param parameters additional parameters for `git log` command
   * @return a list of {@link GitCommit}
   * @throws VcsException if there is a problem with running git
   * @see GitHistoryUtils#formHashParameters(Project, Collection)
   */
  public static List<GitCommit> history(Project project, VirtualFile root, String... parameters)
    throws VcsException {
    VcsLogObjectsFactory factory = GitLogUtil.getObjectsFactoryWithDisposeCheck(project);
    if (factory == null) {
      return Collections.emptyList();
    }

    List<GitCommit> commits = new ArrayList<>();
    try {
      GitLogUtil.readFullDetails(project, root, commits::add, parameters);
    }
    catch (VcsException e) {
      if (commits.isEmpty()) {
        throw e;
      }
      LOG.warn("Error during loading details, returning partially loaded commits\n", e);
    }
    return commits;
  }

  /**
   * Create a proper list of parameters for `git log` command from a list of hashes.
   *
   * @param project a {@link Project} instance for which the command is going to be invoked. Used to get Git version.
   * @param hashes  a list of hashes to call `git log` for
   * @return a array of parameters that could be fed to a `git log` command
   */
  public static String [] formHashParameters(Project project, Collection<String> hashes) {
    List<String> parameters = new ArrayList<>();

    parameters.add(GitLogUtil.getNoWalkParameter(project));
    parameters.addAll(hashes);

    return ArrayUtil.toStringArray(parameters);
  }

  /**
   * Get current revision for the file under git in the current or specified branch.
   *
   * @param project  context project
   * @param filePath file path to the file which revision is to be retrieved
   * @param branch   name of branch or null if current branch wanted
   * @return revision number or null if the file is unversioned or new
   * @throws VcsException if there is a problem with running git
   */
  public static @Nullable VcsRevisionNumber getCurrentRevision(Project project, FilePath filePath,
                                                               @Nullable String branch) throws VcsException {
    filePath = VcsUtil.getLastCommitPath(project, filePath);
    GitRepository repository = GitUtil.getRepositoryForFile(project, filePath);
    GitLineHandler h = new GitLineHandler(project, repository.getRoot(), GitCommand.LOG);
    GitLogParser<GitLogRecord> parser = GitLogParser.createDefaultParser(project, HASH, COMMIT_TIME);
    h.setSilent(true);
    h.addParameters("-n1", parser.getPretty());
    h.addParameters(!StringUtil.isEmpty(branch) && !repository.isFresh() ? branch : "--all");
    h.endOptions();
    h.addRelativePaths(filePath);
    String result = Git.getInstance().runCommand(h).getOutputOrThrow();
    if (result.isEmpty()) {
      return null;
    }
    final GitLogRecord record = parser.parseOneRecord(result);
    if (record == null) {
      return null;
    }
    record.setUsedHandler(h);
    return new GitRevisionNumber(record.getHash(), record.getDate());
  }

  public static @Nullable VcsRevisionDescription getCurrentRevisionDescription(Project project, FilePath filePath)
    throws VcsException {
    filePath = VcsUtil.getLastCommitPath(project, filePath);
    GitLineHandler h = new GitLineHandler(project, GitUtil.getRootForFile(project, filePath), GitCommand.LOG);
    GitLogParser<GitLogRecord> parser = GitLogParser.createDefaultParser(project, HASH, COMMIT_TIME, AUTHOR_NAME, COMMITTER_NAME,
                                                                         SUBJECT, BODY, RAW_BODY);
    h.setSilent(true);
    h.addParameters("-n1", parser.getPretty());
    h.addParameters("--encoding=UTF-8");
    h.addParameters("--all");
    h.endOptions();
    h.addRelativePaths(filePath);
    String result = Git.getInstance().runCommand(h).getOutputOrThrow();
    if (result.isEmpty()) {
      return null;
    }
    final GitLogRecord record = parser.parseOneRecord(result);
    if (record == null) {
      return null;
    }
    record.setUsedHandler(h);

    final String author = Objects.equals(record.getAuthorName(), record.getCommitterName()) ? record.getAuthorName() :
                          record.getAuthorName() + " (" + record.getCommitterName() + ")";
    return new VcsRevisionDescriptionImpl(new GitRevisionNumber(record.getHash(), record.getDate()), record.getDate(), author,
                                          record.getFullMessage());
  }

  /**
   * Get current revision for the file under git.
   *
   * @param project  context project
   * @param filePath file path to the file which revision is to be retrieved
   * @return a revision number or null if the file is unversioned or new
   * @throws VcsException if there is problem with running git
   */
  public static @Nullable ItemLatestState getLastRevision(Project project, FilePath filePath) throws VcsException {
    GitRepository repository = GitUtil.getRepositoryForFile(project, filePath);
    VirtualFile root = repository.getRoot();
    GitBranch c = repository.getCurrentBranch();
    GitBranchTrackInfo info = c == null ? null : repository.getBranchTrackInfo(c.getName());
    GitBranch t = info == null ? null : info.getRemoteBranch();
    if (t == null) {
      return new ItemLatestState(getCurrentRevision(project, filePath, null), true, false);
    }
    filePath = VcsUtil.getLastCommitPath(project, filePath);
    GitLineHandler h = new GitLineHandler(project, root, GitCommand.LOG);
    GitLogParser<GitLogFullRecord> parser = GitLogParser.createDefaultParser(project, GitLogParser.NameStatus.STATUS,
                                                                             HASH, COMMIT_TIME, PARENTS);
    h.setSilent(true);
    h.addParameters("-n1", parser.getPretty(), "--name-status", t.getFullName());
    h.endOptions();
    h.addRelativePaths(filePath);
    String result = Git.getInstance().runCommand(h).getOutputOrThrow();
    if (result.isEmpty()) {
      return null;
    }
    GitLogFullRecord record = parser.parseOneRecord(result);
    if (record == null) {
      return null;
    }
    final List<Change> changes = record.parseChanges(project, root);
    boolean exists = changes.isEmpty() || !FileStatus.DELETED.equals(changes.get(0).getFileStatus());
    record.setUsedHandler(h);
    return new ItemLatestState(new GitRevisionNumber(record.getHash(), record.getDate()), exists, false);
  }

  public static @Nullable String getNumberOfCommitsBetween(GitRepository repository, String from, String to) {
    GitLineHandler handler = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.REV_LIST);
    handler.addParameters("--count", from + ".." + to);
    handler.setSilent(true);
    try {
      return Git.getInstance().runCommand(handler).getOutputOrThrow().trim();
    }
    catch (VcsException e) {
      LOG.debug(e.getMessage());
      return null;
    }
  }

  public static @Nullable GitRevisionNumber getMergeBase(Project project, VirtualFile root, String first,
                                                         String second) throws VcsException {
    GitLineHandler h = new GitLineHandler(project, root, GitCommand.MERGE_BASE);
    h.setSilent(true);
    h.addParameters(first, second);
    GitCommandResult result = Git.getInstance().runCommand(h);
    if (!result.success()) return null;
    String output = result.getOutputAsJoinedString().trim();
    if (output.isEmpty()) return null;
    return GitRevisionNumber.resolve(project, root, output);
  }
}
