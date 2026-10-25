// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.changes;

import consulo.diff.util.Range;
import consulo.diff.util.Side;
import consulo.logging.Logger;
import consulo.util.lang.Pair;
import consulo.versionControlSystem.change.patch.PatchHunk;
import consulo.versionControlSystem.change.patch.PatchHunkUtil;
import consulo.versionControlSystem.change.patch.TextFilePatch;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Holds the {@code patch} between two commits in {@code fileHistory}
 * <pre>
 *       .............................. O  b.txt               \
 *       :           :                  ^                      |
 *       :           :                  |                      |
 *       :           :                  O  a.txt               |
 *       :           v                  ^ \                    |
 *       :         patch                |  \                   |
 *       :     a.txt -> b.txt           O   \                  |
 *       :           ^                  ^    \                 |
 *       :           :                  |     O  a.txt         |
 *       :           :..................O     ^                | fileHistory
 *       :                              ^     |                |
 *       v                              |     O  a.txt         |
 *       Cumulative                     O     ^                |
 *       patch                          ^   /                  |
 *       a.txt -> b.txt                 |  /                   |
 *       ^                              O                      |
 *       :                              ^                      |
 *       :                              |                      |
 *       .............................. O  a.txt               /
 * </pre>
 */
public class GitTextFilePatchWithHistory {
    private static final Logger LOG = Logger.getInstance(GitTextFilePatchWithHistory.class);

    private final TextFilePatch myPatch;
    private final boolean myCumulative;
    private final GitFileHistory myFileHistory;

    /**
     * @param patch       a patch with changes in a file between two commits
     * @param cumulative  {@code true} if {@code patch} is between start and end of {@code fileHistory} which spans from merge base
     *                    to last commit, {@code false} if it is a patch between a pair of adjacent commits in {@code fileHistory}
     * @param fileHistory the whole history of changes for the file (file history)
     */
    public GitTextFilePatchWithHistory(TextFilePatch patch, boolean cumulative, GitFileHistory fileHistory) {
        myPatch = patch;
        myCumulative = cumulative;
        myFileHistory = fileHistory;
    }

    public TextFilePatch getPatch() {
        return myPatch;
    }

    public boolean isCumulative() {
        return myCumulative;
    }

    public GitFileHistory getFileHistory() {
        return myFileHistory;
    }

    /**
     * Check that history contains the specified file at the {@code commitSha}
     * File may have not been changed in this particular commit, but was changed before or after
     */
    public boolean contains(String commitSha, String filePath) {
        return myFileHistory.contains(commitSha, filePath);
    }

    /**
     * Maps a file line {@code lineIndex} in a commit {@code fromCommitSha} to a line on the given {@code side} of the patch
     *
     * @return {@code null} only if the commit cannot be found.
     * Otherwise, this function returns the most accurate line number on the given side of the patch history we could derive.
     * If the line is removed during any patch, its location information can no longer be retrieved exactly, so instead, we
     * continue with the topmost line of the changed hunk.
     */
    public @Nullable Integer forcefullyMapLine(String fromCommitSha, int lineIndex, Side side) {
        // map to merge base, not left revision
        String beforeSha = myCumulative ? Objects.requireNonNull(myFileHistory.findStartCommit()) : Objects.requireNonNull(myPatch.getBeforeVersionId());
        String afterSha = Objects.requireNonNull(myPatch.getAfterVersionId());

        if (fromCommitSha.equals(beforeSha) && side == Side.LEFT) {
            return lineIndex;
        }
        if (fromCommitSha.equals(afterSha) && side == Side.RIGHT) {
            return lineIndex;
        }

        if (side == Side.LEFT) {
            List<TextFilePatch> patches = myFileHistory.getPatchesBetween(beforeSha, fromCommitSha);
            return transferLine(patches, lineIndex, true).line();
        }
        else {
            List<TextFilePatch> patches = myFileHistory.getPatchesBetween(fromCommitSha, afterSha);
            return transferLine(patches, lineIndex, false).line();
        }
    }

    /**
     * Map the {@code lineIndex} in a file in a <i>commit</i> {@code fromCommitSha} to a location in the current patch
     *
     * @param fromCommitSha commit where the line index is known
     * @param lineIndex     index of the text line in a file
     * @param bias          priority of mapping to the parent or child of commit {@code fromCommitSha}
     */
    public @Nullable Pair<Side, Integer> mapLine(String fromCommitSha, int lineIndex, Side bias) {
        // map to merge base, not left revision
        String beforeSha = myCumulative ? Objects.requireNonNull(myFileHistory.findStartCommit()) : Objects.requireNonNull(myPatch.getBeforeVersionId());
        String afterSha = Objects.requireNonNull(myPatch.getAfterVersionId());

        if (fromCommitSha.equals(beforeSha)) {
            return Pair.create(Side.LEFT, lineIndex);
        }
        if (fromCommitSha.equals(afterSha)) {
            return Pair.create(Side.RIGHT, lineIndex);
        }

        if (!myFileHistory.contains(fromCommitSha, MutableLinearGitFileHistory.getFilePath(myPatch))) {
            return null;
        }

        try {
            if (bias == Side.LEFT) {
                Pair<Side, Integer> result = transferToParent(lineIndex, beforeSha, fromCommitSha, afterSha);
                return result != null ? result : transferToChild(lineIndex, beforeSha, fromCommitSha, afterSha);
            }
            else {
                Pair<Side, Integer> result = transferToChild(lineIndex, beforeSha, fromCommitSha, afterSha);
                return result != null ? result : transferToParent(lineIndex, beforeSha, fromCommitSha, afterSha);
            }
        }
        catch (Exception e) {
            LOG.debug(e);
            return null;
        }
    }

    private @Nullable Pair<Side, Integer> transferToParent(int lineIndex, String beforeSha, String fromCommitSha, String afterSha) {
        if (myFileHistory.compare(afterSha, fromCommitSha) < 0) {
            List<TextFilePatch> patches = myFileHistory.getPatchesBetween(afterSha, fromCommitSha);
            Integer location = transferLine(patches, lineIndex, true).exactLocation();
            return location == null ? null : Pair.create(Side.RIGHT, location);
        }
        else if (myFileHistory.compare(beforeSha, fromCommitSha) < 0) {
            List<TextFilePatch> patches = myFileHistory.getPatchesBetween(beforeSha, fromCommitSha);
            Integer location = transferLine(patches, lineIndex, true).exactLocation();
            return location == null ? null : Pair.create(Side.LEFT, location);
        }
        else {
            return null;
        }
    }

    private @Nullable Pair<Side, Integer> transferToChild(int lineIndex, String beforeSha, String fromCommitSha, String afterSha) {
        if (myFileHistory.compare(fromCommitSha, beforeSha) < 0) {
            List<TextFilePatch> patches = myFileHistory.getPatchesBetween(fromCommitSha, beforeSha);
            Integer location = transferLine(patches, lineIndex, false).exactLocation();
            return location == null ? null : Pair.create(Side.LEFT, location);
        }
        else if (myFileHistory.compare(fromCommitSha, afterSha) < 0) {
            List<TextFilePatch> patches = myFileHistory.getPatchesBetween(fromCommitSha, afterSha);
            Integer location = transferLine(patches, lineIndex, false).exactLocation();
            return location == null ? null : Pair.create(Side.RIGHT, location);
        }
        else {
            return null;
        }
    }

    private static TransferResult transferLine(List<TextFilePatch> patchChain, int lineIndex, boolean rightToLeft) {
        double currentLine = lineIndex;
        boolean isEstimate = false;

        List<TextFilePatch> patches = rightToLeft ? reversed(patchChain) : patchChain;

        for (TextFilePatch patch : patches) {
            List<Range> changeOnlyRanges = new ArrayList<>();
            for (PatchHunk hunk : patch.getHunks()) {
                for (Range range : PatchHunkUtil.getChangeOnlyRanges(hunk)) {
                    changeOnlyRanges.add(rightToLeft ? reverseRange(range) : range);
                }
            }

            double offset = 0.0;
            for (Range range : changeOnlyRanges) {
                if (currentLine < range.start1()) {
                    break;
                }
                else if (range.start1() <= currentLine && currentLine < range.end1()) {
                    isEstimate = true;
                    // Careful when changing: we assume that range.end1 - range.start1 is non-zero due to the above check
                    // Estimated position algo: find the relative position of the line in the hunk, then map it to that relative position in the new hunk
                    // A choice can be made about how to map to 'relative position' though; we choose to find the relative position based on the 'center' of the line within a range:
                    //  - in a half-open range [1, 2), line 1 has relative position 0.5 (the 'center' of the line is used rather than the start)
                    //  - in a half-open range [1, 3), line 1 has relative position 0.333, line 2 has relative position 0.667
                    double relativePosition = ((currentLine - range.start1()) + 1) / (double) ((range.end1() - range.start1()) + 1);
                    offset -= currentLine - range.start1(); // move line to the start of the range in the old hunk
                    // We map relative positions back to the output line in the following way:
                    //  - for any relPos and out-range [1, 1) - there no output line -, we map to line 1
                    //  - for any relPos and out-range [1, 2) - there is only 1 possible output line -, we map to line 1.5 (and round after all mapping is done)
                    //  - for relPos = 0.5 and out-range [1, 3) - there are 2 possible output lines -, we map to line 2
                    //  - for relPos = 0.5 and out-range [1, 4) - there are 3 possible output lines -, we map to line 2.5 (and round after all mapping is done)
                    offset += relativePosition * (range.end2() - range.start2()); // then to the relative position in the new hunk
                }
                else if (range.end1() <= currentLine) {
                    offset += (range.end2() - range.start2()) - (range.end1() - range.start1());
                }
            }
            currentLine += offset;
        }

        // Note that the only way for the line number to become floating point is when it IS an estimate
        int line = (int) Math.floor(currentLine);
        return isEstimate ? TransferResult.estimatedTransfer(line) : TransferResult.exactTransfer(line);
    }

    private static Range reverseRange(Range range) {
        return new Range(range.start2(), range.end2(), range.start1(), range.end1());
    }

    private static <T> List<T> reversed(List<T> list) {
        List<T> result = new ArrayList<>(list);
        Collections.reverse(result);
        return result;
    }
}
