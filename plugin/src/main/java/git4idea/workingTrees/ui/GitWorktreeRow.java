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

import consulo.git.icon.GitIconGroup;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.workspace.VcsWorkspaceBadge;
import git4idea.GitWorkingTree;
import git4idea.repo.GitRepository;
import git4idea.ui.branch.GitBranchReview;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public record GitWorktreeRow(GitRepository repository,
                             GitWorkingTree gitWorkingTree,
                             String presentableBranchName,
                             String location,
                             GitRepositoryKind repositoryKind,
                             @Nullable GitBranchReview review) implements GitWorkingTreesListEntry {
    @Override
    public String presentableName() {
        return gitWorkingTree.getPath().getName();
    }

    @Override
    public FilePath getPath() {
        return gitWorkingTree.getPath();
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
        return gitWorkingTree.isCurrent();
    }

    @Override
    public boolean isMain() {
        return gitWorkingTree.isMain();
    }

    @Override
    public boolean isAvailable() {
        return !gitWorkingTree.isPrunable();
    }

    @Override
    public List<VcsWorkspaceBadge> getNameBadges() {
        List<VcsWorkspaceBadge> badges = new ArrayList<>();
        if (repositoryKind == GitRepositoryKind.SUBMODULE) {
            badges.add(VcsWorkspaceBadge.text(GitLocalize.toolwindowWorkingTreesWorktreeKindSubmoduleHint()));
        }
        if (gitWorkingTree.isLocked()) {
            badges.add(VcsWorkspaceBadge.icon(PlatformIconGroup.ideReadonly()));
        }
        return badges;
    }

    @Override
    public List<VcsWorkspaceBadge> getRevisionBadges() {
        GitBranchReview review = this.review;
        return review == null ? List.of() : List.of(new VcsWorkspaceBadge(GitIconGroup.pullrequestopen(), LocalizeValue.of(review.getTitle())));
    }

    @Override
    public LocalizeValue getTooltip() {
        return GitWorktreesUiUtil.tooltipText(this);
    }
}
