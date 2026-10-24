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
package git4idea.ui.branch;

import git4idea.GitLocalBranch;
import git4idea.GitReference;
import git4idea.branch.GitBranchUtil;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRepository;

import java.util.Map;

public final class GitBranchActionsUtil {
    private GitBranchActionsUtil() {
    }

    public static boolean hasTrackingConflicts(Map<GitRepository, GitLocalBranch> conflictingLocalBranches, String remoteBranchName) {
        for (Map.Entry<GitRepository, GitLocalBranch> entry : conflictingLocalBranches.entrySet()) {
            GitBranchTrackInfo trackInfo = GitBranchUtil.getTrackInfoForBranch(entry.getKey(), entry.getValue());
            if (trackInfo != null && !GitReference.BRANCH_NAME_HASHING_STRATEGY.equals(remoteBranchName, trackInfo.remoteBranch().getName())) {
                return true;
            }
        }
        return false;
    }
}
