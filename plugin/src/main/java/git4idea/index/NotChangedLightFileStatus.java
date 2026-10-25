// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.index;

import consulo.virtualFileSystem.status.FileStatus;

import java.util.Objects;

public final class NotChangedLightFileStatus extends LightFileStatus {
    private final String myPath;

    public NotChangedLightFileStatus(String path) {
        myPath = path;
    }

    public String getPath() {
        return myPath;
    }

    @Override
    FileStatus getFileStatus() {
        return FileStatus.NOT_CHANGED;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NotChangedLightFileStatus that && myPath.equals(that.myPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myPath);
    }

    @Override
    public String toString() {
        return "NotChanged(path=" + myPath + ")";
    }
}
