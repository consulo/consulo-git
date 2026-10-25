// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees;

import consulo.application.util.registry.Registry;
import git4idea.GitReference;
import git4idea.GitStandardLocalBranch;
import git4idea.GitWorkingTree;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class GitWorkingTreesUtil {
    public static final String TOOLWINDOW_TAB_ID = "Working Trees";

    public static boolean isWorkingTreesFeatureEnabled() {
        return Registry.is("git.enable.working.trees.feature", true);
    }

    /**
     * Finds the linked working tree that currently has {@code reference} checked out among {@code workingTrees}, or {@code null}
     * if none does. Only local branches are matched: a worktree checks out exactly one local branch, while a
     * remote branch / tag / detached HEAD never blocks reuse.
     *
     * @param skipCurrentWorkingTree exclude the worktree the caller is already in (its "current" one).
     */
    public static @Nullable GitWorkingTree findCheckedOutWorkingTree(GitReference reference,
                                                           Collection<GitWorkingTree> workingTrees,
                                                           boolean skipCurrentWorkingTree) {
        List<GitWorkingTree> trees = findCheckedOutWorkingTrees(reference, workingTrees, skipCurrentWorkingTree);
        return trees.isEmpty() ? null : trees.getFirst();
    }

    /**
     * @return all working trees (among {@code workingTrees}) that have {@code reference} checked out. Normally at most one, but the "ignore other
     * worktrees" override lets a branch be checked out into more than one worktree at a time.
     */
    public static List<GitWorkingTree> findCheckedOutWorkingTrees(GitReference reference,
                                                                  Collection<GitWorkingTree> workingTrees,
                                                                  boolean skipCurrentWorkingTree) {
        if (!isWorkingTreesFeatureEnabled() || !(reference instanceof GitStandardLocalBranch)) {
            return List.of();
        }
        List<GitWorkingTree> result = new ArrayList<>();
        for (GitWorkingTree tree : workingTrees) {
            if ((!skipCurrentWorkingTree || !tree.isCurrent()) && reference.equals(tree.getCurrentBranch())) {
                result.add(tree);
            }
        }
        return result;
    }

    /**
     * Collapses repositories that are actually working trees of the <i>same</i> underlying git repository.
     * <p>
     * A repository and each of its linked working trees can independently be registered as VCS roots of a
     * (multi-root) project. They all report the same working-tree list, so, left as-is, the Worktrees view would show
     * one entry per registered root with identical, duplicated worktree lists.
     * <p>
     * Grouping is keyed by {@code commonGitDirPath} — the git directory shared by all working trees of a repository — which is
     * the only identity that holds for every layout:
     * <ul>
     * <li>a <i>bare</i> repository has no main checkout at all, and its administrative entry is not reported as a working tree,
     *   so no registered root reports a {@link GitWorkingTree#isMain()} entry to key on;</li>
     * <li>for a <i>submodule</i>, git reports the main working tree at the submodule's git directory rather than at its
     *   checkout, so the submodule and its linked working trees would disagree about that path.</li>
     * </ul>
     * Of each group, the repository rooted at the main working tree (the main checkout) survives when it is registered;
     * otherwise — a bare repository, or a project that registered only linked working trees — the root that sorts first
     * does, so that the choice is stable across sessions. Genuinely distinct repositories (peers, submodules, plain
     * nested repositories) have distinct git directories and are left untouched.
     */
    public static <T> List<T> mergeLinkedWorktreeRepositories(List<T> repositories,
                                                              Function<T, String> rootPath,
                                                              Function<T, String> commonGitDirPath,
                                                              Function<T, Collection<GitWorkingTree>> workingTrees) {
        Map<String, List<T>> groups = new LinkedHashMap<>();
        for (T repository : repositories) {
            groups.computeIfAbsent(commonGitDirPath.apply(repository), key -> new ArrayList<>()).add(repository);
        }
        List<T> result = new ArrayList<>();
        for (List<T> group : groups.values()) {
            // The main checkout is the root whose own working tree is the main one; a bare repository has no such root.
            T main = null;
            for (T repository : group) {
                if (workingTrees.apply(repository).stream().anyMatch(it -> it.isCurrent() && it.isMain())) {
                    main = repository;
                    break;
                }
            }
            result.add(main != null ? main : group.stream().min(Comparator.comparing(rootPath)).orElseThrow());
        }
        return result;
    }

    private GitWorkingTreesUtil() {
    }
}
