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
package git4idea.ui.branch.popup;

import consulo.ui.image.Image;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.branch.DvcsInOutCountersInRepo;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsRef;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsRepositoryModel;
import consulo.versionControlSystem.util.VcsUtil;
import git4idea.GitBranch;
import git4idea.GitLocalBranch;
import git4idea.GitReference;
import git4idea.branch.GitBranchIncomingOutgoingManager;
import git4idea.branch.GitBranchType;
import git4idea.branch.GitBranchUtil;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRepository;
import git4idea.ui.branch.GitBranchManager;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * {@link DvcsRepositoryModel} adapter over a real {@link GitRepository}.
 * <p>
 * Favorite state is delegated to {@link GitBranchManager}; current-ref state is resolved from the
 * repository's current branch. Tags are never current or favorite here.
 *
 * @author VISTALL
 */
public record GitDvcsRepositoryModel(GitRepository repository, GitBranchManager branchManager) implements DvcsRepositoryModel {
    @Override
    public String getShortName() {
        return DvcsUtil.getShortRepositoryName(repository);
    }

    @Override
    public boolean isCurrentRef(DvcsRef ref) {
        GitReference reference = unwrap(ref);
        GitLocalBranch current = repository.getCurrentBranch();
        return current != null && current.equals(reference);
    }

    @Override
    public boolean isFavorite(DvcsRef ref) {
        GitReference reference = unwrap(ref);
        if (!(reference instanceof GitBranch branch)) {
            return false;
        }
        GitBranchType type = branch.isRemote() ? GitBranchType.REMOTE : GitBranchType.LOCAL;
        return branchManager.isFavorite(type, repository, branch.getName());
    }

    @Nullable
    @Override
    public String getTrackedBranchName(DvcsRef ref) {
        GitReference reference = unwrap(ref);
        if (!(reference instanceof GitLocalBranch localBranch)) {
            return null;
        }
        GitBranchTrackInfo trackInfo = GitBranchUtil.getTrackInfoForBranch(repository, localBranch);
        return trackInfo != null ? trackInfo.remoteBranch().getName() : null;
    }

    @Nullable
    @Override
    public DvcsInOutCountersInRepo getInOutCounters(DvcsRef ref) {
        GitReference reference = unwrap(ref);
        if (!(reference instanceof GitLocalBranch localBranch)) {
            return null;
        }
        return GitBranchIncomingOutgoingManager.getInstance(repository.getProject()).getIncomingOutgoingCounters(repository, localBranch);
    }

    @Nullable
    @Override
    public Instant getLastFetchTime() {
        return GitBranchIncomingOutgoingManager.getInstance(repository.getProject()).getLastFetchTime();
    }

    @Override
    public FilePath getRoot() {
        return VcsUtil.getFilePath(repository.getRoot());
    }

    @Nullable
    @Override
    public Image getIcon() {
        return null;
    }

    @Nullable
    private static GitReference unwrap(DvcsRef ref) {
        return ref instanceof GitRefAdapter adapter ? adapter.reference() : null;
    }
}
