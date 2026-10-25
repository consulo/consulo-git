// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.changes;

import consulo.versionControlSystem.change.patch.TextFilePatch;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * Represents a history of changes in a single file
 */
public interface GitFileHistory extends Comparator<String> {
    /**
     * Find first commit in history
     */
    @Nullable String findStartCommit();

    /**
     * Find parent commit of {@code commitSha}
     */
    @Nullable String findFirstParent(String commitSha);

    /**
     * Check if file history contains a record about {@code filePath} at or prior to commit {@code commitSha}
     */
    boolean contains(String commitSha, String filePath);

    /**
     * Compare two commits in history to find the parent-chile relation
     *
     * @return less than 0 if {@code commitSha1} is a parent of {@code commitSha2}
     * 0 if it's the same commit
     * more than 0 if {@code commitSha2} is a parent of {@code commitSha1}
     */
    @Override
    int compare(String commitSha1, String commitSha2);

    /**
     * Retrieve a chain of patches between commits {@code parent} and {@code child}
     * Including {@code child}, but excluding {@code parent}
     */
    List<TextFilePatch> getPatchesBetween(String parent, String child);
}
