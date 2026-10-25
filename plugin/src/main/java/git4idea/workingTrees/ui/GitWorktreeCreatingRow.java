// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.workingTrees.ui;

import consulo.localize.LocalizeValue;
import consulo.versionControlSystem.FilePath;
import git4idea.repo.GitRepository;

/**
 * A worktree whose target directory doesn't exist as a real GitWorkingTree yet: {@code git worktree add} is still running.
 */
public record GitWorktreeCreatingRow(GitRepository repository,
                                     FilePath targetPath,
                                     String presentableBranchName,
                                     String location) implements GitWorkingTreesListEntry {
    @Override
    public String presentableName() {
        return targetPath.getName();
    }

    @Override
    public FilePath getPath() {
        return targetPath;
    }

    @Override
    public String getName() {
        return presentableName();
    }

    @Override
    public LocalizeValue getRevisionText() {
        return LocalizeValue.of(presentableBranchName);
    }

    @Override
    public boolean isCurrent() {
        return false;
    }

    @Override
    public boolean isPending() {
        return true;
    }

    @Override
    public LocalizeValue getTooltip() {
        return GitWorktreesUiUtil.tooltipText(this);
    }
}
