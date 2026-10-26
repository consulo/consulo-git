// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.push;

import git4idea.GitRemoteBranch;
import git4idea.branch.GitBranchUtil;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

public final class GitPushRepoResultUtil {
    private GitPushRepoResultUtil() {
    }

    public static @Nullable GitRemoteBranch findRemoteBranch(GitPushRepoResult pushResult, GitRepository repository) {
        return repository.getBranches().findRemoteBranch(GitBranchUtil.stripRefsPrefix(pushResult.getTargetBranch()));
    }
}
