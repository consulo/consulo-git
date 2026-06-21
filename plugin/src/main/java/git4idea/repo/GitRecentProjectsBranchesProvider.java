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
package git4idea.repo;

import consulo.annotation.component.ExtensionImpl;
import consulo.project.RecentProjectsBranchesProvider;
import consulo.versionControlSystem.distributed.DvcsUtil;
import git4idea.GitUtil;
import git4idea.branch.GitBranchUtil;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Stateless reader of the current Git branch for a <b>closed</b> recent project, shown on the Welcome screen.
 * <p>
 * Unlike the JetBrains implementation, this provider owns no cache, no service and no refresh machinery: all caching,
 * expiry and persistence live in the platform ({@code RecentProjectsManagerImpl}). This class answers a single question
 * with a pure, blocking, off-EDT read of {@code .git/HEAD}.
 * <p>
 * Reftable repositories (where {@code .git/HEAD} is a {@code ref: refs/heads/.invalid} stub) are not resolved here and
 * report no branch.
 */
@ExtensionImpl
public class GitRecentProjectsBranchesProvider implements RecentProjectsBranchesProvider {
    private static final String REF_PREFIX = "ref:";

    // Reftable format: .git/HEAD is a stub pointing to an ".invalid" ref; the real HEAD lives in binary reftable files.
    private static final String REFTABLE_STUB = ".invalid";

    @Nullable
    @Override
    public String getCurrentBranch(String projectPath) {
        File headFile = findGitHead(projectPath);
        if (headFile == null) {
            return null;
        }

        String content = DvcsUtil.tryLoadFileOrReturn(headFile, null, StandardCharsets.UTF_8);
        if (content == null) {
            return null;
        }
        content = content.trim();

        // A raw hash means a detached HEAD - not on a branch.
        if (!content.startsWith(REF_PREFIX)) {
            return null;
        }

        String target = content.substring(REF_PREFIX.length()).trim();
        String branch = GitBranchUtil.stripRefsPrefix(target);
        if (branch.isEmpty() || branch.equals(REFTABLE_STUB)) {
            return null;
        }
        return branch;
    }

    /**
     * Walks up from the project path looking for a {@code .git} directory (or a {@code .git} file with a {@code gitdir:}
     * redirect, e.g. submodules and worktrees), and returns its {@code HEAD} file if it exists.
     */
    @Nullable
    private static File findGitHead(String projectPath) {
        for (File dir = new File(projectPath); dir != null; dir = dir.getParentFile()) {
            File gitDir = GitUtil.findGitDir(dir);
            if (gitDir != null) {
                File headFile = new File(gitDir, GitUtil.HEAD);
                return headFile.exists() ? headFile : null;
            }
        }
        return null;
    }
}
