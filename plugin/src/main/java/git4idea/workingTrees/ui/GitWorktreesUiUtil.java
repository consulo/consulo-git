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

import consulo.application.util.UserHomeFileUtil;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.util.io.FileUtil;
import consulo.versionControlSystem.FilePath;
import git4idea.GitStandardLocalBranch;
import git4idea.GitTag;
import git4idea.GitWorkingTree;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.repo.GitSubmodule;
import git4idea.ui.branch.GitBranchReview;
import git4idea.workingTrees.GitWorkingTreesUtil;
import git4idea.workingTrees.GitWorktreePendingCreation;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Stateless helpers backing the Worktrees tab.
 */
public final class GitWorktreesUiUtil {
    public static List<GitRepository> getRepositories(Project project) {
        if (!GitWorkingTreesUtil.isWorkingTreesFeatureEnabled()) {
            return List.of();
        }

        GitRepositoryManager manager = GitRepositoryManager.getInstance(project);
        if (manager == null) {
            return List.of();
        }

        // A linked working tree can be registered as its own VCS root; collapse it into its underlying repository.
        return GitWorkingTreesUtil.mergeLinkedWorktreeRepositories(
            manager.getRepositories(),
            it -> it.getRoot().getPath(),
            it -> it.getRepositoryFiles().getCommonGitDir().getPath(),
            it -> it.getWorkingTreeHolder().getWorkingTrees()
        );
    }

    public static List<GitWorktreeGroup> buildEntries(Project project,
                                                      Map<FilePath, GitWorktreePendingCreation> pendingCreations,
                                                      Function<GitWorktreeBranchKey, @Nullable GitBranchReview> reviews) {
        List<GitRepository> repositories = getRepositories(project);
        Map<GitRepository, List<GitWorktreePendingCreation>> pendingByRepository = new HashMap<>();
        for (GitWorktreePendingCreation pending : pendingCreations.values()) {
            pendingByRepository.computeIfAbsent(pending.repository(), it -> new ArrayList<>()).add(pending);
        }
        return buildWorkingTreesEntries(
            repositories,
            GitWorktreesUiUtil::findTagNameForCommit,
            repository -> resolveRepositoryKind(repository, repositories),
            repository -> pendingByRepository.getOrDefault(repository, List.of()),
            reviews
        );
    }

    public static List<GitWorktreeGroup> buildWorkingTreesEntries(
        List<GitRepository> repositories,
        BiFunction<GitRepository, String, @Nullable String> tagNameForCommit,
        Function<GitRepository, GitRepositoryKind> repositoryKind,
        Function<GitRepository, List<GitWorktreePendingCreation>> pendingCreations,
        Function<GitWorktreeBranchKey, @Nullable GitBranchReview> reviews
    ) {
        List<GitWorktreeGroup> result = new ArrayList<>();
        repositories.stream()
            .sorted(Comparator.comparing(it -> it.getRoot().getPath()))
            .forEach(it -> result.add(
                new GitWorktreeGroup(it, buildRepositoryEntries(it, tagNameForCommit, repositoryKind, pendingCreations, reviews))
            ));
        return result;
    }

    private static List<GitWorkingTreesListEntry> buildRepositoryEntries(
        GitRepository repository,
        BiFunction<GitRepository, String, @Nullable String> tagNameForCommit,
        Function<GitRepository, GitRepositoryKind> repositoryKind,
        Function<GitRepository, List<GitWorktreePendingCreation>> pendingCreations,
        Function<GitWorktreeBranchKey, @Nullable GitBranchReview> reviews
    ) {
        GitRepositoryKind kind = repositoryKind.apply(repository);
        List<GitWorkingTreesListEntry> entries = new ArrayList<>();
        repository.getWorkingTreeHolder().getWorkingTrees().stream()
            .sorted(Comparator.comparing((GitWorkingTree it) -> !it.isMain()))
            .forEach(wt -> {
                GitStandardLocalBranch branch = wt.getCurrentBranch();
                entries.add(new GitWorktreeRow(
                    repository,
                    wt,
                    resolvePresentableBranchName(wt, headHash -> tagNameForCommit.apply(repository, headHash)),
                    UserHomeFileUtil.getLocationRelativeToUserHome(wt.getPath().getPath()),
                    kind,
                    branch == null ? null : reviews.apply(new GitWorktreeBranchKey(repository, branch))
                ));
            });
        for (GitWorktreePendingCreation pending : pendingCreations.apply(repository)) {
            entries.add(new GitWorktreeCreatingRow(
                repository,
                pending.targetPath(),
                pending.presentableBranchName(),
                UserHomeFileUtil.getLocationRelativeToUserHome(pending.targetPath().getPath())
            ));
        }
        return entries;
    }

    // Branch and tag names are Git identifiers, not translatable UI text.
    private static String resolvePresentableBranchName(GitWorkingTree worktree, Function<String, @Nullable String> tagName) {
        GitStandardLocalBranch currentBranch = worktree.getCurrentBranch();
        if (currentBranch != null) {
            return currentBranch.getName();
        }
        String headHash = worktree.getHeadHash();
        if (headHash != null) {
            String tag = tagName.apply(headHash);
            if (tag != null) {
                return tag;
            }
        }
        return GitLocalize.toolwindowWorkingTreesTabDetachedWorkingTreeBranchText().get();
    }

    public static LocalizeValue tooltipText(GitWorkingTreesListEntry entry) {
        return switch (entry) {
            case GitWorktreeRow row -> worktreeTooltipText(
                row.location(),
                row.gitWorkingTree().isMain(),
                row.gitWorkingTree().isLocked(),
                row.gitWorkingTree().isPrunable(),
                row.repositoryKind()
            );
            case GitWorktreeCreatingRow row -> LocalizeValue.of(row.location());
        };
    }

    private static LocalizeValue worktreeTooltipText(String location, boolean isMain, boolean locked, boolean prunable, GitRepositoryKind kind) {
        List<LocalizeValue> info = new ArrayList<>();
        info.add(isMain ? GitLocalize.toolwindowWorkingTreesWorktreeKindMain() : GitLocalize.toolwindowWorkingTreesWorktreeKindLinked());
        if (locked) {
            info.add(GitLocalize.toolwindowWorkingTreesWorktreeStatusLocked());
        }
        if (prunable) {
            info.add(GitLocalize.toolwindowWorkingTreesWorktreeStatusPrunable());
        }
        List<LocalizeValue> lines = new ArrayList<>();
        lines.add(LocalizeValue.join(" \u00b7 ", info.toArray(LocalizeValue[]::new)));
        if (isMain && kind == GitRepositoryKind.SUBMODULE) {
            lines.add(GitLocalize.toolwindowWorkingTreesSubmoduleRelinkWarning());
        }
        lines.add(LocalizeValue.of(location));
        return LocalizeValue.join("\n", lines.toArray(LocalizeValue[]::new));
    }

    public static boolean anyRepositoryHasMultipleWorktrees(Project project) {
        return getRepositories(project).stream().anyMatch(it -> it.getWorkingTreeHolder().getWorkingTrees().size() > 1);
    }

    public static boolean isEmpty(Project project) {
        return getRepositories(project).isEmpty();
    }

    private static @Nullable String findTagNameForCommit(GitRepository repository, String headHash) {
        List<GitTag> tags = repository.getTagsHolder().getTagsForCommit(headHash);
        return tags.isEmpty() ? null : tags.getFirst().getName();
    }

    private static GitRepositoryKind resolveRepositoryKind(GitRepository repository, List<GitRepository> allRepositories) {
        if (GitSubmodule.isSubmodule(repository)) {
            return GitRepositoryKind.SUBMODULE;
        }
        String path = repository.getRoot().getPath();
        boolean nested = allRepositories.stream()
            .anyMatch(it -> it != repository && FileUtil.isAncestor(it.getRoot().getPath(), path, true));
        return nested ? GitRepositoryKind.NESTED : GitRepositoryKind.TOP_LEVEL;
    }

    private GitWorktreesUiUtil() {
    }
}
