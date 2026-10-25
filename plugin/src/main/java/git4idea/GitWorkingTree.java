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
package git4idea;

import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.util.VcsUtil;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * {@link #getCurrentBranch()} is null when working tree is detached
 */
public final class GitWorkingTree {
    /**
     * A {@link GitWorkingTree} known only by its {@code path}. Git reported the path, but the repository model has no other
     * record of it, so the branch, the flags, and the head hash stay at their unknown defaults.
     */
    public static GitWorkingTree pathOnly(String path) {
        return new GitWorkingTree(path, null, false, false);
    }

    private final FilePath myPath;
    private final @Nullable GitStandardLocalBranch myCurrentBranch;
    private final boolean myMain;
    private final boolean myCurrent;
    private final boolean myLocked;
    private final boolean myPrunable;
    private final @Nullable String myHeadHash;

    public GitWorkingTree(FilePath path,
                          @Nullable GitStandardLocalBranch currentBranch,
                          boolean isMain,
                          boolean isCurrent,
                          boolean isLocked,
                          boolean isPrunable,
                          @Nullable String headHash) {
        myPath = path;
        myCurrentBranch = currentBranch;
        myMain = isMain;
        myCurrent = isCurrent;
        myLocked = isLocked;
        myPrunable = isPrunable;
        myHeadHash = headHash;
    }

    public GitWorkingTree(String path,
                          @Nullable String fullBranchName,
                          boolean isMain,
                          boolean isCurrent) {
        this(path, fullBranchName, isMain, isCurrent, false, false, null);
    }

    public GitWorkingTree(String path,
                          @Nullable String fullBranchName,
                          boolean isMain,
                          boolean isCurrent,
                          boolean isLocked,
                          boolean isPrunable,
                          @Nullable String headHash) {
        this(
            VcsUtil.getFilePath(path, true),
            fullBranchName == null ? null : new GitStandardLocalBranch(fullBranchName),
            isMain,
            isCurrent,
            isLocked,
            isPrunable,
            headHash
        );
    }

    public FilePath getPath() {
        return myPath;
    }

    public @Nullable GitStandardLocalBranch getCurrentBranch() {
        return myCurrentBranch;
    }

    public boolean isMain() {
        return myMain;
    }

    public boolean isCurrent() {
        return myCurrent;
    }

    /**
     * A locked working tree is never pruned, i.e., removed from a list of existing working trees.
     * Also, locking prevents working trees from being moved or deleted.
     * Locking is useful for working trees on portable devices or network shares which are not always mounted.
     *
     * @see #isPrunable()
     */
    public boolean isLocked() {
        return myLocked;
    }

    /**
     * Means that the working tree can be pruned by the prune command.
     * This working tree is not considered valid by git, and its administrative files in {@code $GIT_DIR/worktrees} are considered stale and
     * a subject to remove on {@code git worktree prune} command or on eventual automatic cleanup (see {@code gc.worktreePruneExpire} in git-config).
     *
     * @see #isLocked()
     */
    public boolean isPrunable() {
        return myPrunable;
    }

    public @Nullable String getHeadHash() {
        return myHeadHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GitWorkingTree that)) {
            return false;
        }
        return myMain == that.myMain
            && myCurrent == that.myCurrent
            && myLocked == that.myLocked
            && myPrunable == that.myPrunable
            && myPath.equals(that.myPath)
            && Objects.equals(myCurrentBranch, that.myCurrentBranch)
            && Objects.equals(myHeadHash, that.myHeadHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(myPath, myCurrentBranch, myMain, myCurrent, myLocked, myPrunable, myHeadHash);
    }

    @Override
    public String toString() {
        return "GitWorkingTree(path=" + myPath + ", currentBranch=" + myCurrentBranch + ", isMain=" + myMain + ", isCurrent=" + myCurrent
            + ", isLocked=" + myLocked + ", isPrunable=" + myPrunable + ", headHash=" + myHeadHash + ")";
    }
}
