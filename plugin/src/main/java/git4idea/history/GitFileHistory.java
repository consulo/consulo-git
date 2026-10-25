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

import consulo.application.util.registry.Registry;
import consulo.project.Project;
import consulo.util.lang.Couple;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.history.VcsFileRevision;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.log.base.VcsFileStatusInfo;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitContentRevision;
import git4idea.GitFileRevision;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandler;
import git4idea.config.GitVersionSpecialty;
import git4idea.log.GitLogProvider;
import git4idea.repo.GitRepositoryManager;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An implementation of file history algorithm with renames detection.
 * <p>
 * 'git log --follow' does detect renames, but it has a bug - merge commits aren't handled properly: they just disappear from the history.
 * See http://kerneltrap.org/mailarchive/git/2009/1/30/4861054 and the whole thread about that: --follow is buggy, but maybe it won't be fixed.
 * To get the whole history through renames we do the following:
 * 1. 'git log &lt;file&gt;' - and we get the history since the first rename, if there was one.
 * 2. 'git show -M --follow --name-status &lt;first_commit_id&gt; -- &lt;file&gt;'
 * where &lt;first_commit_id&gt; is the hash of the first commit in the history we got in #1.
 * With this command we get the rename-detection-friendly information about the first commit of the given file history.
 * (by specifying the &lt;file&gt; we filter out other changes in that commit; but in that case rename detection requires '--follow' to work,
 * that's safe for one commit though)
 * If the first commit was ADDING the file, then there were no renames with this file, we have the full history.
 * But if the first commit was RENAMING the file, we are going to query for the history before rename.
 * Now we have the previous name of the file:
 * <p>
 * ~/sandbox/git # git show --oneline --name-status -M 4185b97
 * 4185b97 renamed a to b
 * R100    a       b
 * <p>
 * 3. 'git log &lt;rename_commit_id&gt; -- &lt;previous_file_name&gt;' - get the history of a before the given commit.
 * We need to specify &lt;rename_commit_id&gt; here, because &lt;previous_file_name&gt; could have some new history, which has nothing common with our &lt;file&gt;.
 * Then we repeat 2 and 3 until the first commit is ADDING the file, not RENAMING it.
 * <p>
 * TODO: handle multiple repositories configuration: a file can be moved from one repo to another
 */
public class GitFileHistory {
    private final Project myProject;
    private final VirtualFile myRoot;
    private final FilePath myPath;
    private final List<String> myStartingRevisions;
    private final boolean myFullHistory;

    GitFileHistory(Project project, VirtualFile root, FilePath path, List<String> startingRevisions, boolean fullHistory) {
        myProject = project;
        myRoot = root;
        myPath = VcsUtil.getLastCommitPath(project, path);
        myStartingRevisions = startingRevisions;
        myFullHistory = fullHistory;
    }

    GitFileHistory(Project project, VirtualFile root, FilePath path, List<String> startingRevisions) {
        this(project, root, path, startingRevisions, false);
    }

    void load(Consumer<GitFileRevision> consumer, String... parameters) throws VcsException {
        load(consumer, rename -> {
        }, parameters);
    }

    void load(Consumer<GitFileRevision> consumer, Consumer<FileRename> renameConsumer, String... parameters) throws VcsException {
        GitLogParser<GitLogFullRecord> logParser = createLogParser(myProject);

        Set<String> visitedCommits = new HashSet<>();
        LinkedList<FileHistoryStart> starts = new LinkedList<>();
        starts.add(new FileHistoryStart(myStartingRevisions, myPath));
        while (!starts.isEmpty()) {
            FileHistoryStart start = starts.removeFirst();
            List<String> logParameters = new ArrayList<>(start.revisions());
            logParameters.addAll(Arrays.asList(parameters));
            List<String> lastCommits = runGitLog(logParser, start.path(), visitedCommits, consumer, logParameters);
            if (lastCommits.isEmpty()) {
                continue;
            }

            for (String lastCommit : lastCommits) {
                List<FileHistoryStart> parents = getParentsAndPathsIfRename(lastCommit, start.path());
                for (FileHistoryStart parent : parents) {
                    if (!parent.path().equals(start.path())) {
                        for (String prevRevision : parent.revisions()) {
                            renameConsumer.accept(new FileRename(prevRevision, lastCommit, parent.path(), start.path()));
                        }
                    }
                }
                starts.addAll(parents);
            }
        }
    }

    FilePath getFilePath() {
        return myPath;
    }

    private List<String> runGitLog(GitLogParser<GitLogFullRecord> logParser,
                                   FilePath startPath,
                                   Set<String> visitedCommits,
                                   Consumer<GitFileRevision> consumer,
                                   List<String> parameters) throws VcsException {
        GitLineHandler handler = createLogHandler(logParser, startPath, parameters);
        boolean[] skipFurtherOutput = {false};
        List<String> lastCommits = new ArrayList<>();
        GitLogOutputSplitter<GitLogFullRecord> splitter = new GitLogOutputSplitter<>(handler, logParser, record -> {
            if (skipFurtherOutput[0] || visitedCommits.contains(record.getHash())) {
                return;
            }

            visitedCommits.add(record.getHash());
            List<VcsFileStatusInfo> statusInfos = record.getStatusInfos();
            if (!statusInfos.isEmpty() && statusInfos.getFirst().getType() == Change.Type.NEW && !myPath.isDirectory()) {
                lastCommits.add(record.getHash());
                if (!myFullHistory) {
                    skipFurtherOutput[0] = true;
                }
            }
            GitFileRevision revision = createGitFileRevision(myProject, myRoot, record, startPath);
            consumer.accept(revision);
        });
        Git.getInstance().runCommandWithoutCollectingOutput(handler).throwOnError();
        splitter.reportErrors();
        return lastCommits;
    }

    /**
     * Gets info of the given commit and checks if a file was renamed there.
     * Returns a list of pairs consisting of the older file path, which file was renamed from and a parent commit hash as a string.
     */
    private List<FileHistoryStart> getParentsAndPathsIfRename(String commit, FilePath filePath) throws VcsException {
        GitCommitRequirements requirements = new GitCommitRequirements(
            true,
            GitCommitRequirements.DiffRenames.Limit.Default.INSTANCE,
            GitCommitRequirements.DiffInMergeCommits.DIFF_TO_PARENTS
        );
        GitLineHandler h = new GitLineHandler(myProject, myRoot, GitCommand.SHOW, requirements.configParameters());
        GitLogParser<GitLogFullRecord> parser =
            GitLogParser.createDefaultParser(myProject, GitLogParser.NameStatus.STATUS, GitLogParser.GitLogOption.PARENTS);
        h.setStdoutSuppressed(true);
        h.addParameters(requirements.commandParameters(myProject));
        h.addParameters("--follow", "--name-status", parser.getPretty(), "--encoding=UTF-8", commit);
        h.endOptions();
        h.addRelativePaths(filePath);

        String output = Git.getInstance().runCommand(h).getOutputOrThrow();
        List<GitLogFullRecord> records = parser.parse(output);

        if (records.isEmpty()) {
            return List.of();
        }

        String[] parentsHashes = records.getFirst().getParentsHashes();

        // each record correspond to a parent of the target commit (since DIFF_TO_PARENTS was passed to the "git show" command)
        List<FileHistoryStart> result = new ArrayList<>();
        for (int i = 0; i < Math.min(parentsHashes.length, records.size()); i++) {
            for (VcsFileStatusInfo info : records.get(i).getStatusInfos()) {
                if (info.isRenamed()) {
                    result.add(new FileHistoryStart(List.of(parentsHashes[i]), GitContentRevision.createPath(myRoot, info.getFirstPath())));
                    break;
                }
            }
        }
        if (records.size() < parentsHashes.length && myFullHistory) {
            for (int i = records.size(); i < parentsHashes.length; i++) {
                String parent = parentsHashes[i];
                VcsFileStatusInfo rename = GitLogHistoryHandler.getRename(myProject, myRoot, parent, commit, filePath);
                if (rename == null) {
                    continue;
                }
                result.add(new FileHistoryStart(List.of(parent), GitContentRevision.createPath(myRoot, rename.getFirstPath())));
            }
        }

        return result;
    }

    private GitLineHandler createLogHandler(GitLogParser<GitLogFullRecord> parser, FilePath path, List<String> parameters) {
        GitLineHandler h = new GitLineHandler(myProject, myRoot, GitCommand.LOG);
        h.setStdoutSuppressed(true);
        h.addParameters("--name-status", parser.getPretty(), "--encoding=UTF-8");
        if (GitVersionSpecialty.FULL_HISTORY_SIMPLIFY_MERGES_WORKS_CORRECTLY.existsIn(myProject) && Registry.is("git.file.history.full", true)) {
            h.addParameters("--full-history", "--simplify-merges");
        }
        h.addParameters(parameters);
        h.endOptions();
        h.addRelativePaths(path);
        return h;
    }

    /**
     * Starting conditions for computing file history: target path and a list of commit hashes,
     * branch names or parameters such as "--branches", "--remotes" or "--tags".
     */
    private record FileHistoryStart(List<String> revisions, FilePath path) {
    }

    private static @Nullable FilePath filePath(GitLogFullRecord record, VirtualFile root) {
        List<VcsFileStatusInfo> statusInfos = record.getStatusInfos();
        if (statusInfos.isEmpty()) {
            return null;
        }
        VcsFileStatusInfo statusInfo = statusInfos.getFirst();
        String secondPath = statusInfo.getSecondPath();
        return VcsUtil.getFilePath(root.getPath() + "/" + (secondPath != null ? secondPath : statusInfo.getFirstPath()), false);
    }

    static GitFileRevision createGitFileRevision(Project project, VirtualFile root, GitLogFullRecord record, FilePath filePath) {
        GitRevisionNumber revision = new GitRevisionNumber(record.getHash(), record.getDate());
        Couple<String> authorPair = Couple.of(record.getAuthorName(), record.getAuthorEmail());
        Couple<String> committerPair = Couple.of(record.getCommitterName(), record.getCommitterEmail());
        List<String> parents = Arrays.asList(record.getParentsHashes());
        FilePath recordPath = filePath(record, root);
        FilePath revisionPath = recordPath != null ? recordPath : filePath;
        List<VcsFileStatusInfo> statusInfos = record.getStatusInfos();
        boolean deleted = !statusInfos.isEmpty() && statusInfos.getFirst().getType() == Change.Type.DELETED;
        return new GitFileRevision(project, root, revisionPath, revision, Couple.of(authorPair, committerPair),
            record.getFullMessage(),
            null, new Date(record.getAuthorTimeStamp()), parents, deleted);
    }

    static GitLogParser<GitLogFullRecord> createLogParser(Project project) {
        return GitLogParser.createDefaultParser(project, GitLogParser.NameStatus.STATUS, GitLogParser.GitLogOption.HASH,
            GitLogParser.GitLogOption.COMMIT_TIME,
            GitLogParser.GitLogOption.AUTHOR_NAME, GitLogParser.GitLogOption.AUTHOR_EMAIL, GitLogParser.GitLogOption.COMMITTER_NAME,
            GitLogParser.GitLogOption.COMMITTER_EMAIL, GitLogParser.GitLogOption.PARENTS,
            GitLogParser.GitLogOption.SUBJECT, GitLogParser.GitLogOption.BODY, GitLogParser.GitLogOption.RAW_BODY,
            GitLogParser.GitLogOption.AUTHOR_TIME);
    }

    static void loadHistoryOrThrow(Project project,
                                   FilePath path,
                                   @Nullable VcsRevisionNumber startingFrom,
                                   Consumer<GitFileRevision> consumer,
                                   Consumer<FileRename> renameConsumer,
                                   String... parameters) throws VcsException {
        VirtualFile detectedRoot = GitUtil.getRootForFile(project, path);
        VirtualFile repositoryRoot = GitLogProvider.getCorrectedVcsRoot(GitRepositoryManager.getInstance(project), detectedRoot, path);
        String revision = startingFrom != null ? startingFrom.asString() : GitUtil.HEAD;
        new GitFileHistory(project, repositoryRoot, path, List.of(revision)).load(consumer, renameConsumer, parameters);
    }

    /**
     * Get history for the file starting from specific revision and feed it to the consumer.
     *
     * @param project           Context project.
     * @param path              FilePath which history is queried.
     * @param startingFrom      Revision from which to start file history, when null history is started from HEAD revision.
     * @param consumer          This consumer is notified when new history records are retrieved.
     * @param exceptionConsumer This consumer is notified in case of error while executing git command.
     * @param renameConsumer    This consumer is notified when rename records are retrieved.
     * @param parameters        Optional parameters which will be added to the git log command just before the path.
     */
    public static void loadHistory(Project project,
                                   FilePath path,
                                   @Nullable VcsRevisionNumber startingFrom,
                                   Consumer<? super GitFileRevision> consumer,
                                   Consumer<? super VcsException> exceptionConsumer,
                                   Consumer<FileRename> renameConsumer,
                                   String... parameters) {
        try {
            loadHistoryOrThrow(project, path, startingFrom, consumer::accept, renameConsumer, parameters);
        }
        catch (VcsException e) {
            exceptionConsumer.accept(e);
        }
    }

    public static void loadHistory(Project project,
                                   FilePath path,
                                   @Nullable VcsRevisionNumber startingFrom,
                                   Consumer<? super GitFileRevision> consumer,
                                   Consumer<? super VcsException> exceptionConsumer,
                                   String... parameters) {
        loadHistory(project, path, startingFrom, consumer, exceptionConsumer, rename -> {
        }, parameters);
    }

    /**
     * Get history for the file starting from specific revision.
     *
     * @param project      the context project
     * @param path         the file path
     * @param startingFrom revision from which to start file history
     * @param parameters   optional parameters which will be added to the git log command just before the path
     * @return list of the revisions
     * @throws VcsException if there is problem with running git
     */
    public static List<VcsFileRevision> collectHistoryForRevision(Project project,
                                                                  FilePath path,
                                                                  VcsRevisionNumber startingFrom,
                                                                  String... parameters) throws VcsException {
        List<VcsFileRevision> revisions = new ArrayList<>();
        loadHistoryOrThrow(project, path, startingFrom, revisions::add, rename -> {
        }, parameters);
        return revisions;
    }

    /**
     * Get history for the file.
     *
     * @param project    the context project
     * @param path       the file path
     * @param parameters optional parameters which will be added to the git log command just before the path
     * @return list of the revisions
     * @throws VcsException if there is problem with running git
     */
    public static List<VcsFileRevision> collectHistory(Project project, FilePath path, String... parameters) throws VcsException {
        return collectHistoryForRevision(project, path, GitRevisionNumber.HEAD, parameters);
    }
}
