// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.commands;

import consulo.util.dataholder.Key;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects the error which happens when {@code git checkout}/{@code git switch}/{@code git worktree add} refuses to
 * check out a branch that is already checked out in another worktree of the same repository, e.g.:
 * <pre>
 * fatal: 'my-branch' is already used by worktree at '/path/to/other/worktree'
 * </pre>
 * Git before 2.43 worded this message differently:
 * <pre>
 * fatal: 'my-branch' is already checked out at '/path/to/other/worktree'
 * </pre>
 */
public final class GitBranchAlreadyCheckedOutInOtherWorktreeDetector implements GitLineEventDetector {
    private static final Pattern PATTERN = Pattern.compile(".*fatal: '(.*)' is already (?:used by worktree|checked out) at '(.*)'.*");

    public static @Nullable GitBranchAlreadyCheckedOutInOtherWorktreeMatch matchInOutput(List<String> lines) {
        GitBranchAlreadyCheckedOutInOtherWorktreeDetector detector = new GitBranchAlreadyCheckedOutInOtherWorktreeDetector();
        for (String line : lines) {
            detector.onLineAvailable(line, null);
            if (detector.isDetected()) {
                return detector.getMatch();
            }
        }
        return null;
    }

    private volatile @Nullable GitBranchAlreadyCheckedOutInOtherWorktreeMatch myMatch;

    public @Nullable GitBranchAlreadyCheckedOutInOtherWorktreeMatch getMatch() {
        return myMatch;
    }

    @Override
    public boolean isDetected() {
        return myMatch != null;
    }

    @Override
    public void onLineAvailable(@Nullable String line, @Nullable Key outputType) {
        if (line == null) {
            return;
        }

        Matcher matcher = PATTERN.matcher(line);
        if (!matcher.matches()) {
            return;
        }
        myMatch = new GitBranchAlreadyCheckedOutInOtherWorktreeMatch(matcher.group(1), matcher.group(2));
    }

    @Override
    public void processTerminated(int exitCode) {
    }

    @Override
    public void startFailed(Throwable exception) {
    }
}
