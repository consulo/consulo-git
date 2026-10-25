// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.index;

import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.status.FileStatus;
import git4idea.GitContentRevision;
import org.jspecify.annotations.Nullable;

public record GitFileStatus(char index, char workTree, FilePath path, @Nullable FilePath origPath) {
    public static GitFileStatus untrackedStatus(FilePath filePath) {
        return new GitFileStatus('?', '?', filePath, null);
    }

    public static GitFileStatus ignoredStatus(FilePath filePath) {
        return new GitFileStatus('!', '!', filePath, null);
    }

    public static GitFileStatus notChangedStatus(FilePath filePath) {
        return new GitFileStatus(' ', ' ', filePath, null);
    }

    public GitFileStatus(char index, char workTree, FilePath path) {
        this(index, workTree, path, null);
    }

    public GitFileStatus(VirtualFile root, StatusRecordLightFileStatus record) {
        this(record.getIndex(),
             record.getWorkTree(),
             GitContentRevision.createPath(root, record.getPath()),
             record.getOrigPath() == null ? null : GitContentRevision.createPath(root, record.getOrigPath()));
    }

    public boolean isConflicted() {
        return GitIndexStatusUtil.isConflicted(index, workTree);
    }

    public boolean isUntracked() {
        return GitIndexStatusUtil.isUntracked(index) || GitIndexStatusUtil.isUntracked(workTree);
    }

    public boolean isIgnored() {
        return GitIndexStatusUtil.isIgnored(index) || GitIndexStatusUtil.isIgnored(workTree);
    }

    public boolean isTracked() {
        return !GitIndexStatusUtil.isIgnored(index) && !GitIndexStatusUtil.isUntracked(index);
    }

    public boolean isNotChanged() {
        return index == ' ' && workTree == ' ' && origPath == null;
    }

    public @Nullable FileStatus getStagedStatus() throws VcsException {
        return GitIndexStatusUtil.isIgnored(index) || GitIndexStatusUtil.isUntracked(index) || isConflicted()
            ? null
            : GitIndexStatusUtil.getFileStatus(index);
    }

    public @Nullable FileStatus getUnStagedStatus() throws VcsException {
        return GitIndexStatusUtil.isIgnored(workTree) || GitIndexStatusUtil.isUntracked(workTree) || isConflicted()
            ? null
            : GitIndexStatusUtil.getFileStatus(workTree);
    }

    public boolean has(ContentVersion contentVersion) {
        return switch (contentVersion) {
            case HEAD -> isTracked() && !GitIndexStatusUtil.isAdded(index) && !GitIndexStatusUtil.isIntendedToBeAdded(index, workTree);
            case STAGED -> isTracked() && !GitIndexStatusUtil.isDeleted(index);
            case LOCAL -> !GitIndexStatusUtil.isDeleted(workTree);
        };
    }

    public FilePath path(ContentVersion contentVersion) {
        return switch (contentVersion) {
            case HEAD -> origPath != null ? origPath : path;
            case STAGED -> GitIndexStatusUtil.isRenamed(index) ? path : origPath != null ? origPath : path;
            case LOCAL -> path;
        };
    }
}
