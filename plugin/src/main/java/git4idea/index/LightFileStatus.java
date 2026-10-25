// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.index;

import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.status.FileStatus;
import org.jspecify.annotations.Nullable;

public sealed abstract class LightFileStatus permits BlankLightFileStatus, NotChangedLightFileStatus, StatusRecordLightFileStatus {
    abstract FileStatus getFileStatus() throws VcsException;

    public boolean isTracked() {
        return switch (this) {
            case BlankLightFileStatus blank -> false;
            case NotChangedLightFileStatus notChanged -> true;
            case StatusRecordLightFileStatus record ->
                !GitIndexStatusUtil.isIgnored(record.getIndex()) && !GitIndexStatusUtil.isUntracked(record.getIndex());
        };
    }

    public @Nullable String getRepositoryPath() {
        return switch (this) {
            case BlankLightFileStatus blank -> null;
            case NotChangedLightFileStatus notChanged -> notChanged.getPath();
            case StatusRecordLightFileStatus record -> !isTracked() || record.getIndex() == 'A' || record.getWorkTree() == 'A'
                ? null
                : record.getOrigPath() != null ? record.getOrigPath() : record.getPath();
        };
    }
}
