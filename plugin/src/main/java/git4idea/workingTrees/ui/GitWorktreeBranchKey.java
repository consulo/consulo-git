// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees.ui;

import git4idea.GitStandardLocalBranch;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

public record GitWorktreeBranchKey(GitRepository repository, GitStandardLocalBranch branch) {
    public static @Nullable GitWorktreeBranchKey resolveReviewBranchKey(GitWorktreeRow row) {
        GitStandardLocalBranch branch = row.gitWorkingTree().getCurrentBranch();
        return branch == null ? null : new GitWorktreeBranchKey(row.repository(), branch);
    }
}
