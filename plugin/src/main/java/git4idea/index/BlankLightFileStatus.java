// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.index;

import consulo.virtualFileSystem.status.FileStatus;

public final class BlankLightFileStatus extends LightFileStatus {
    public static final BlankLightFileStatus INSTANCE = new BlankLightFileStatus();

    private BlankLightFileStatus() {
    }

    @Override
    FileStatus getFileStatus() {
        return FileStatus.NOT_CHANGED;
    }

    @Override
    public String toString() {
        return "Blank";
    }
}
