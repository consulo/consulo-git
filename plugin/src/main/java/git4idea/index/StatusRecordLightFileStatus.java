// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.index;

import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.status.FileStatus;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

public final class StatusRecordLightFileStatus extends LightFileStatus {
    private final char myIndex;
    private final char myWorkTree;
    private final String myPath;
    private final @Nullable String myOrigPath;

    public StatusRecordLightFileStatus(char index, char workTree, String path) {
        this(index, workTree, path, null);
    }

    public StatusRecordLightFileStatus(char index, char workTree, String path, @Nullable String origPath) {
        myIndex = index;
        myWorkTree = workTree;
        myPath = path;
        myOrigPath = origPath;
    }

    public char getIndex() {
        return myIndex;
    }

    public char getWorkTree() {
        return myWorkTree;
    }

    public String getPath() {
        return myPath;
    }

    public @Nullable String getOrigPath() {
        return myOrigPath;
    }

    @Override
    FileStatus getFileStatus() throws VcsException {
        if (isConflicted()) {
            return FileStatus.MERGED_WITH_CONFLICTS;
        }
        FileStatus status = GitIndexStatusUtil.getFileStatus(myIndex);
        if (status == null) {
            status = GitIndexStatusUtil.getFileStatus(myWorkTree);
        }
        return status != null ? status : FileStatus.NOT_CHANGED;
    }

    boolean isConflicted() {
        return GitIndexStatusUtil.isConflicted(myIndex, myWorkTree);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StatusRecordLightFileStatus that
            && myIndex == that.myIndex
            && myWorkTree == that.myWorkTree
            && myPath.equals(that.myPath)
            && Objects.equals(myOrigPath, that.myOrigPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myIndex, myWorkTree, myPath, myOrigPath);
    }

    @Override
    public String toString() {
        return "StatusRecord(index=" + myIndex + ", workTree=" + myWorkTree + ", path=" + myPath + ", origPath=" + myOrigPath + ")";
    }
}
