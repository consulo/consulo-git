// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.changes;

import consulo.versionControlSystem.change.patch.TextFilePatch;
import org.jspecify.annotations.Nullable;

record MutableLinearGitFileHistoryEntry(@Nullable TextFilePatch patch, @Nullable String filePath) {
    MutableLinearGitFileHistoryEntry(@Nullable TextFilePatch patch) {
        this(patch, patch == null ? null : MutableLinearGitFileHistory.getFilePath(patch));
    }
}
