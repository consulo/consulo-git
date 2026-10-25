// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees;

import consulo.logging.Logger;
import git4idea.GitWorkingTree;
import git4idea.util.StringScanner;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class GitWorktreeListParser {
    private static final Logger LOG = Logger.getInstance(GitWorktreeListParser.class);

    private final Path myCurrentRoot;
    private final @Nullable Path myCurrentGitDir;

    private int myBadLineReported = 0;
    private @Nullable String myCurrentWorktreePath;
    private @Nullable String myBranchFullName;
    private boolean myDetached = false;
    private boolean myLocked = false;
    private boolean myPrunable = false;
    private @Nullable String myCurrentHeadHash;
    private boolean myFirst = true;
    private final List<GitWorkingTree> myTrees = new ArrayList<>();

    public GitWorktreeListParser(Path currentRoot, @Nullable Path currentGitDir) {
        myCurrentRoot = currentRoot;
        myCurrentGitDir = currentGitDir;
    }

    public List<GitWorkingTree> parseOrEmpty(List<String> lines) {
        lines.forEach(this::handleLine);
        return List.copyOf(myTrees);
    }

    private void handleLine(String line) {
        if (line.isBlank()) {
            createWorkingTree(myCurrentWorktreePath, myBranchFullName, myDetached, myFirst, myLocked, myPrunable, myCurrentHeadHash);
            return;
        }

        try {
            if (line.equals("detached")) {
                myDetached = true;
                return;
            }
            if (line.equals("locked")) {
                myLocked = true;
                return;
            }
            StringScanner scanner = new StringScanner(line);
            String name = scanner.spaceToken();
            if (name == null) {
                return;
            }
            String value = scanner.line();
            if (value == null) {
                return;
            }
            switch (name) {
                case "worktree" -> myCurrentWorktreePath = value;
                case "HEAD" -> myCurrentHeadHash = value;
                case "branch" -> myBranchFullName = value;
                case "prunable" -> myPrunable = true;
                case "locked" -> myLocked = true;
                default -> report(line, null);
            }
        }
        catch (RuntimeException e) {
            report(line, e);
        }
    }

    private void createWorkingTree(@Nullable String path,
                                   @Nullable String branch,
                                   boolean detached,
                                   boolean main,
                                   boolean locked,
                                   boolean prunable,
                                   @Nullable String headHash) {
        if (path == null) {
            LOG.warn("'worktree' wasn't reported for branch " + (branch == null ? "<detached>" : branch));
        }
        else if (!detached && branch == null) {
            LOG.warn("'branch' wasn't reported for path " + path);
        }
        else {
            Path convertedPath = Path.of(path);
            boolean isCurrent = convertedPath.equals(myCurrentRoot) || (myCurrentGitDir != null && convertedPath.equals(myCurrentGitDir));
            // For a submodule, git reports the git-dir as the worktree path; expose the real working-tree root instead.
            Path effectivePath = isCurrent ? myCurrentRoot : convertedPath;
            String hash = detached ? headHash : null;
            myTrees.add(new GitWorkingTree(effectivePath.toString(), branch, main, isCurrent, locked, prunable, hash));
        }
        myCurrentWorktreePath = null;
        myBranchFullName = null;
        myDetached = false;
        myLocked = false;
        myPrunable = false;
        myCurrentHeadHash = null;
        myFirst = false;
    }

    private void report(String line, @Nullable Throwable e) {
        myBadLineReported++;
        if (myBadLineReported < 5) {
            LOG.warn("Unexpected worktree output: " + line, e);
        }
    }
}
