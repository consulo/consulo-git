// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.index;

import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.status.FileStatus;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandler;
import git4idea.config.GitVersion;
import git4idea.config.GitVersionSpecialty;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/*
 * `git status` output format (porcelain version 1):
 *
 * XY PATH
 * XY PATH\u0000ORIG_PATH
 *
 * X          Y     Meaning
 * -------------------------------------------------
 * 	        [AMD]   not updated
 * M        [ MD]   updated in index
 * A        [ MD]   added to index
 * D                deleted from index
 * R        [ MD]   renamed in index
 * C        [ MD]   copied in index
 * [MARC]           index and work tree matches
 * [ MARC]     M    work tree changed since index
 * [ MARC]     D    deleted in work tree
 * [ D]        R    renamed in work tree
 * [ D]        C    copied in work tree
 * -------------------------------------------------
 * D           D    unmerged, both deleted
 * A           U    unmerged, added by us
 * U           D    unmerged, deleted by them
 * U           A    unmerged, added by them
 * D           U    unmerged, deleted by us
 * A           A    unmerged, both added
 * U           U    unmerged, both modified
 * -------------------------------------------------
 * ?           ?    untracked
 * !           !    ignored
 * -------------------------------------------------
 */
public final class GitIndexStatusUtil {
    public static final String NUL = "\u0000";

    public static List<GitFileStatus> getStatus(Project project,
                                                VirtualFile root,
                                                List<FilePath> files,
                                                boolean withRenames,
                                                boolean withUntracked,
                                                boolean withIgnored) throws VcsException {
        return getStatus(project, root, files, withRenames, withUntracked, withIgnored, false);
    }

    public static List<GitFileStatus> getStatus(Project project,
                                                VirtualFile root,
                                                List<FilePath> files,
                                                boolean withRenames,
                                                boolean withUntracked,
                                                boolean withIgnored,
                                                boolean expandIgnoredDirectories) throws VcsException {
        List<GitFileStatus> result = new ArrayList<>();
        for (StatusRecordLightFileStatus record : getFileStatus(project, root, files, withRenames, withUntracked, withIgnored, expandIgnoredDirectories)) {
            result.add(new GitFileStatus(root, record));
        }
        return result;
    }

    public static List<StatusRecordLightFileStatus> getFileStatus(Project project,
                                                                  VirtualFile root,
                                                                  List<FilePath> files,
                                                                  boolean withRenames,
                                                                  boolean withUntracked,
                                                                  boolean withIgnored,
                                                                  boolean expandIgnoredDirectories) throws VcsException {
        GitLineHandler h = GitUtil.createHandlerWithPaths(files, () -> {
            GitLineHandler handler = new GitLineHandler(project, root, GitCommand.STATUS);
            handler.setSilent(true);
            GitVcs vcs = GitVcs.getInstance(project);
            appendParameters(handler, vcs == null ? GitVersion.NULL : vcs.getVersion(),
                             withRenames, withUntracked, withIgnored, expandIgnoredDirectories);
            return handler;
        });

        String output = Git.getInstance().runCommand(h).getOutputOrThrow();
        return parseGitStatusOutput(output);
    }

    private static void appendParameters(GitLineHandler handler,
                                         GitVersion gitVersion,
                                         boolean withRenames,
                                         boolean withUntracked,
                                         boolean withIgnored,
                                         boolean expandIgnoredDirectories) {
        handler.addParameters("--porcelain", "-z");
        if (!withRenames) {
            if (GitVersionSpecialty.STATUS_SUPPORTS_NO_RENAMES.existsIn(gitVersion)) {
                handler.addParameters("--no-renames");
            }
        }
        handler.addParameters("--untracked-files=" + (withUntracked ? "all" : "no"));
        if (withIgnored && expandIgnoredDirectories) {
            // The traditional mode lists each file under an ignored directory. The "matching" mode used below collapses
            // the directory into one entry instead, even for a file requested explicitly by path.
            handler.addParameters("--ignored");
        }
        else if (GitVersionSpecialty.STATUS_SUPPORTS_IGNORED_MODES.existsIn(gitVersion)) {
            handler.addParameters(withIgnored ? "--ignored=matching" : "--ignored=no");
        }
        else if (withIgnored) {
            handler.addParameters("--ignored");
        }
    }

    private static List<StatusRecordLightFileStatus> parseGitStatusOutput(String output) throws VcsException {
        List<StatusRecordLightFileStatus> result = new ArrayList<>();

        String[] split = output.split(NUL);
        Iterator<String> it = List.of(split).iterator();
        while (it.hasNext()) {
            String line = it.next();
            if (line.isBlank()) {
                continue; // skip empty lines if any (e.g. the whole output may be empty on a clean working tree).
            }
            if (line.startsWith("starting fsmonitor-daemon in ")) {
                continue; // skip debug output from experimental daemon in git-for-windows-2.33
            }
            // format: XY_filename where _ stands for space.
            if (line.length() < 4 || line.charAt(2) != ' ') { // X, Y, space and at least one symbol for the file
                throwGFE(GitLocalize.statusExceptionMessageLineIsTooShort(), output, line, '0', '0');
            }

            char xStatus = line.charAt(0);
            char yStatus = line.charAt(1);
            if (!isKnownStatus(xStatus) || !isKnownStatus(yStatus)) {
                throwGFE(GitLocalize.statusExceptionMessageUnexpected(), output, line, xStatus, yStatus);
            }

            String pathPart = line.substring(3); // skipping the space
            if (isRenamed(xStatus) || isRenamed(yStatus)) {
                if (!it.hasNext()) {
                    throwGFE(GitLocalize.statusExceptionMessageMissingPath(), output, line, xStatus, yStatus);
                    continue;
                }
                String origPath = it.next(); // read the "from" filepath which is separated also by NUL character.
                result.add(new StatusRecordLightFileStatus(xStatus, yStatus, pathPart, origPath));
            }
            else {
                result.add(new StatusRecordLightFileStatus(xStatus, yStatus, pathPart));
            }
        }

        return result;
    }

    private static void throwGFE(LocalizeValue message, String output, String line, char xStatus, char yStatus) throws VcsException {
        throw new VcsException(GitLocalize.statusExceptionMessageFormatMessageXstatusYstatusLineOutput(message, xStatus, yStatus, line, output));
    }

    private static boolean isKnownStatus(char status) {
        return status == ' ' || status == 'M' || status == 'A' || status == 'D' || status == 'C' || status == 'R' || status == 'U'
            || status == 'T' || status == '!' || status == '?';
    }

    static @Nullable FileStatus getFileStatus(char status) throws VcsException {
        return switch (status) {
            case ' ' -> null;
            case 'M', 'R', 'C', 'T' -> FileStatus.MODIFIED;
            case 'A' -> FileStatus.ADDED;
            case 'D' -> FileStatus.DELETED;
            case 'U' -> FileStatus.MERGED_WITH_CONFLICTS;
            case '!' -> FileStatus.IGNORED;
            case '?' -> FileStatus.UNKNOWN;
            default -> throw new VcsException(GitLocalize.statusExceptionMessageUnexpectedStatus(status));
        };
    }

    public static boolean isIgnored(char status) {
        return status == '!';
    }

    public static boolean isUntracked(char status) {
        return status == '?';
    }

    public static boolean isRenamed(char status) {
        return status == 'R' || status == 'C';
    }

    public static boolean isAdded(char status) {
        return status == 'A';
    }

    public static boolean isIntendedToBeAdded(char index, char workTree) {
        return index == ' ' && workTree == 'A';
    }

    public static boolean isDeleted(char status) {
        return status == 'D';
    }

    public static boolean isConflicted(char index, char workTree) {
        return (index == 'D' && workTree == 'D') ||
            (index == 'A' && workTree == 'A') ||
            (index == 'T' && workTree == 'T') ||
            (index == 'U' || workTree == 'U');
    }

    private GitIndexStatusUtil() {
    }
}
