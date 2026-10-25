// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.changes;

import consulo.diff.DiffComputer;
import consulo.diff.comparison.ComparisonManager;
import consulo.diff.comparison.iterable.DiffIterable;
import consulo.diff.comparison.iterable.DiffIterableUtil;
import consulo.diff.fragment.LineFragment;
import consulo.diff.util.LineOffsets;
import consulo.diff.util.LineOffsetsUtil;
import consulo.diff.util.Range;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.lang.Pair;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ContentRevision;
import consulo.versionControlSystem.change.RefComparisonChange;
import consulo.versionControlSystem.change.patch.FilePatch;
import consulo.versionControlSystem.change.patch.PatchHunk;
import consulo.versionControlSystem.change.patch.PatchHunkUtil;
import consulo.versionControlSystem.change.patch.TextFilePatch;
import consulo.versionControlSystem.util.RangesBuilder;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitContentRevision;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a set of changes in a branch compared to some other branch via three-dot-diff (via merge base)
 */
public interface GitBranchComparisonResult {
    /**
     * SHA hash of the tip of the "left" side of the comparison
     * <p>
     * Typically, the tip of the branch where a review is merged
     */
    String getBaseSha();

    /**
     * The most recent common ancestor of the "left" and "right" sides of the comparison
     * https://git-scm.com/docs/git-merge-base
     */
    String getMergeBaseSha();

    /**
     * SHA hash of the tip of the "right" side of the comparison
     * <p>
     * Typically, the tip of the reviewed branch
     */
    String getHeadSha();

    /**
     * The list of "cumulative" changes between {@link #getBaseSha()} and {@link #getHeadSha()} via {@link #getMergeBaseSha()}
     * In essense - changes between {@link #getMergeBaseSha()} and {@link #getHeadSha()}
     */
    List<RefComparisonChange> getChanges();

    /**
     * Ordered list of commits in the "right" branch where the last commit is the commit with {@link #getHeadSha()}
     */
    List<GitCommitShaWithPatches> getCommits();

    /**
     * Changes between each commit in {@link #getCommits()} and it's first parent
     */
    Map<String, List<RefComparisonChange>> getChangesByCommits();

    /**
     * A container for parsed changes for both {@link #getChanges()} and {@link #getChangesByCommits()}
     * <p>
     * A parsed change is represented by the {@link GitTextFilePatchWithHistory} which holds the parsed diff data of the file change
     * and a reference to the whole history of file changes in the "right" (code review) branch
     */
    Map<RefComparisonChange, GitTextFilePatchWithHistory> getPatchesByChange();

    default @Nullable RefComparisonChange findCumulativeChange(String commitSha, String filePath) {
        for (RefComparisonChange change : getChanges()) {
            GitTextFilePatchWithHistory patch = getPatchesByChange().get(change);
            if (patch != null && patch.contains(commitSha, filePath)) {
                return change;
            }
        }
        return null;
    }

    /**
     * Create a three-dot-diff comparison result holder
     *
     * @param vcsRoot      root of the git repository
     * @param baseSha      head revision of the "left" branch
     * @param mergeBaseSha merge base revision
     * @param commits      commits from the "right" branch (not present in the "left")
     *                     where the last commit should be the head of the "right" branch
     * @param headPatches  patches between merge base and last commit of the "right" branch
     */
    static GitBranchComparisonResult create(Project project,
                                            VirtualFile vcsRoot,
                                            String baseSha,
                                            String mergeBaseSha,
                                            List<GitCommitShaWithPatches> commits,
                                            List<FilePatch> headPatches) {
        return new GitBranchComparisonResultImpl(project, vcsRoot, baseSha, mergeBaseSha, commits, headPatches);
    }

    static @Nullable DiffComputer getDiffComputer(GitTextFilePatchWithHistory patchWithHistory) {
        TextFilePatch patch = patchWithHistory.getPatch();
        if (patch.getHunks().isEmpty()) {
            Logger.getInstance(GitBranchComparisonResult.class).info("Empty diff in patch " + patch);
            return null;
        }
        List<Range> diffRanges = new ArrayList<>();
        for (PatchHunk hunk : patch.getHunks()) {
            diffRanges.addAll(PatchHunkUtil.getChangeOnlyRanges(hunk));
        }
        return (text1, text2, policy, innerChanges, indicator) -> {
            ComparisonManager comparisonManager = ComparisonManager.getInstance();
            LineOffsets lineOffsets1 = LineOffsetsUtil.create(text1);
            LineOffsets lineOffsets2 = LineOffsetsUtil.create(text2);

            if (!RangesBuilder.isValidRanges(text1, text2, lineOffsets1, lineOffsets2, diffRanges)) {
                throw new IllegalStateException("Invalid diff line ranges for " + patch.getAfterName() + " in " + patch.getAfterVersionId());
            }
            DiffIterable iterable = DiffIterableUtil.create(diffRanges, lineOffsets1.getLineCount(), lineOffsets2.getLineCount());
            List<LineFragment> fragments = new ArrayList<>();
            for (Pair<Range, Boolean> pair : DiffIterableUtil.iterateAll(iterable)) {
                fragments.addAll(comparisonManager.compareLinesInner(pair.first, text1, text2, lineOffsets1, lineOffsets2, policy, innerChanges,
                    indicator));
            }
            return fragments;
        };
    }

    static Change createVcsChange(RefComparisonChange change, Project project) {
        ContentRevision beforeRevision = change.filePathBefore() == null
            ? null
            : GitContentRevision.createRevision(change.filePathBefore(), change.revisionNumberBefore(), project);
        ContentRevision afterRevision = change.filePathAfter() == null
            ? null
            : GitContentRevision.createRevision(change.filePathAfter(), change.revisionNumberAfter(), project);
        return new Change(beforeRevision, afterRevision);
    }

    static String getFilePath(FilePatch patch) {
        String afterName = patch.getAfterName();
        return afterName != null ? afterName : patch.getBeforeName();
    }
}
