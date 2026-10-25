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

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.application.Application;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.review.icon.ReviewIconGroup;
import consulo.ui.image.Image;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.distributed.localize.DistributedVcsLocalize;
import consulo.versionControlSystem.distributed.repository.Repository;
import git4idea.GitLocalBranch;
import git4idea.GitTag;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.branch.GitBranchIncomingOutgoingManager;
import git4idea.branch.GitBranchSyncStatus;
import git4idea.branch.GitBranchUtil;
import git4idea.repo.GitRefUtil;
import git4idea.repo.GitRepoInfo;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

/**
 * Supplies a branch presentation to the git branch widget.
 */
@ExtensionAPI(ComponentScope.APPLICATION)
public interface GitCurrentBranchPresenter {
    static Presentation getPresentationFor(GitRepository repository) {
        Presentation presentation = findCustomPresentation(repository);
        return presentation != null ? presentation : getDefaultPresentation(repository);
    }

    /**
     * @return the first presentation an extension supplies, or {@code null} when none applies
     */
    static @Nullable Presentation findCustomPresentation(GitRepository repository) {
        Presentation[] result = new Presentation[1];
        Application.get().getExtensionPoint(GitCurrentBranchPresenter.class).forEach(presenter -> {
            if (result[0] == null) {
                result[0] = presenter.getPresentation(repository);
            }
        });
        return result[0];
    }

    /**
     * Consider notifying {@link GitCurrentBranchPresentationUpdatedListener} when the presentation should be updated.
     *
     * @return a customized presentation for the git widget or null if it is not applicable .
     */
    @Nullable Presentation getPresentation(GitRepository repository);

    interface Presentation {
        @Nullable Image getIcon();

        LocalizeValue getText();

        LocalizeValue getDescription();

        GitBranchSyncStatus getSyncStatus();
    }

    record PresentationData(@Nullable Image icon,
                            LocalizeValue text,
                            LocalizeValue description,
                            GitBranchSyncStatus syncStatus) implements Presentation {
        public PresentationData(@Nullable Image icon, LocalizeValue text, LocalizeValue description) {
            this(icon, text, description, GitBranchSyncStatus.SYNCED);
        }

        @Override
        public @Nullable Image getIcon() {
            return icon;
        }

        @Override
        public LocalizeValue getText() {
            return text;
        }

        @Override
        public LocalizeValue getDescription() {
            return description;
        }

        @Override
        public GitBranchSyncStatus getSyncStatus() {
            return syncStatus;
        }
    }

    private static Presentation getDefaultPresentation(GitRepository repository) {
        return new PresentationData(
            calcIcon(repository),
            LocalizeValue.of(StringUtil.escapeMnemonics(GitBranchUtil.getDisplayableBranchText(repository))),
            calcTooltip(repository),
            GitBranchSyncStatus.calcForCurrentBranch(repository)
        );
    }

    private static @Nullable Image calcIcon(GitRepository repository) {
        if (repository.getState() == Repository.State.NORMAL) {
            return null;
        }
        if (repository.getState() == Repository.State.DETACHED && GitRefUtil.getCurrentReference(repository) instanceof GitTag) {
            return ReviewIconGroup.reviewBranchlabel();
        }
        return PlatformIconGroup.generalWarning();
    }

    private static LocalizeValue calcTooltip(GitRepository repository) {
        GitRepoInfo repoInfo = repository.getInfo();
        GitLocalBranch currentBranch = repoInfo.getCurrentBranch();
        if (repoInfo.getState() == Repository.State.DETACHED) {
            return GitLocalize.gitStatusBarWidgetTooltipDetached();
        }
        if (repoInfo.getState() == Repository.State.REBASING) {
            return GitLocalize.gitStatusBarWidgetTooltipRebasing();
        }
        if (currentBranch != null) {
            LocalizeValue message = DistributedVcsLocalize.tooltipBranchWidgetVcsBranchNameText(GitVcs.NAME, currentBranch.getName());
            if (!GitUtil.justOneGitRepository(repository.getProject())) {
                message = LocalizeValue.join(message, LocalizeValue.of("\n"),
                    DistributedVcsLocalize.tooltipBranchWidgetRootNameText(repository.getRoot().getName()));
            }

            LocalizeValue incomingOutgoingTooltip = GitBranchIncomingOutgoingManager.getInstance(repository.getProject())
                .getIncomingOutgoingState(repository, currentBranch)
                .calcTooltip();
            if (incomingOutgoingTooltip.isNotEmpty()) {
                message = LocalizeValue.join(message, LocalizeValue.of("\n"), incomingOutgoingTooltip);
            }
            return message;
        }
        return LocalizeValue.empty();
    }
}
