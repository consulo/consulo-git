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
package git4idea.checkin;

import consulo.git.localize.GitLocalize;
import consulo.logging.Logger;
import consulo.process.ProcessOutputType;
import consulo.project.Project;
import consulo.util.dataholder.Key;
import consulo.util.io.FileUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.log.Hash;
import consulo.versionControlSystem.log.base.HashImpl;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitHandlerInputProcessorUtil;
import git4idea.commands.GitLineHandler;
import git4idea.commands.GitLineHandlerAdapter;
import git4idea.index.GitIndexUtil;
import git4idea.repo.GitRepository;
import git4idea.util.StringScanner;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

class GitIndexInfoStagingAreaStateManager implements GitStagingAreaStateManager {
    private static final String STATUS_ORDINARY = "1";
    private static final String STATUS_UNMERGED = "u";
    private static final String SUBMODULE_NONE = "N...";
    private static final Logger LOG = Logger.getInstance(GitIndexInfoStagingAreaStateManager.class);

    private final GitRepository myRepository;
    private final Project myProject;
    private final VirtualFile myRoot;
    private List<StagedChange.Staged> mySavedStagedChanges = List.of();

    GitIndexInfoStagingAreaStateManager(GitRepository repository) {
        myRepository = repository;
        myProject = repository.getProject();
        myRoot = repository.getRoot();
    }

    @Override
    public void prepareStagingArea(Set<FilePath> toAdd, Set<FilePath> toRemove) throws VcsException {
        Set<FilePath> pathsToIgnore = new HashSet<>(toAdd);
        pathsToIgnore.addAll(toRemove);
        String rootPath = myRoot.getPath();
        List<StagedChange> all = listStagedChanges(myRepository);
        if (all.stream().anyMatch(it -> it instanceof StagedChange.Unmerged)) {
            throw new VcsException(GitLocalize.errorCommitCantCommitWithUnmergedPaths());
        }

        List<StagedChange.Staged> staged = new ArrayList<>();
        for (StagedChange change : all) {
            if (change instanceof StagedChange.Staged stagedChange) {
                staged.add(stagedChange);
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Found staged changes: " + getStagedLogString(rootPath, staged));
        }

        mySavedStagedChanges = staged.stream().filter(it -> !pathsToIgnore.contains(it.getFilePath())).toList();
        LOG.info("Staged changes excluded for commit: " + getStagedLogString(rootPath, mySavedStagedChanges));

        resetExcluded();
    }

    @Override
    public void restore() {
        List<String> updateIndexLines = new ArrayList<>();
        for (StagedChange.Staged change : mySavedStagedChanges) {
            String line = createUpdateIndexLine(change, false);
            if (line != null) {
                updateIndexLines.add(line);
            }
        }
        if (!updateIndexLines.isEmpty()) {
            try {
                LOG.debug("Restoring staged changes after commit");
                GitLineHandler handler = new GitLineHandler(myProject, myRoot, GitCommand.UPDATE_INDEX);
                handler.addParameters("--index-info");
                handler.setInputProcessor(GitHandlerInputProcessorUtil.writeLines(updateIndexLines, handler.getCharset()));
                Git.getInstance().runCommand(handler).throwOnError();
            }
            catch (VcsException e) {
                LOG.warn(e);
            }
        }
    }

    private List<StagedChange> listStagedChanges(GitRepository repository) throws VcsException {
        List<StagedChange> result = new ArrayList<>();
        GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.STATUS);
        h.addParameters("--porcelain=2");
        h.addParameters("--no-renames");
        h.addParameters("--no-ahead-behind");
        h.addParameters("--no-show-stash");
        h.addParameters("--untracked-files=no");
        h.addParameters("--ignored=no");
        h.endOptions();

        h.addLineListener(new GitLineHandlerAdapter() {
            @Override
            public void onLineAvailable(String line, Key outputType) {
                if (ProcessOutputType.isStdout(outputType)) {
                    parseStatusLinePorcelainV2(line, result);
                }
            }
        });
        Git.getInstance().runCommand(h).throwOnError();
        return result;
    }

    private void parseStatusLinePorcelainV2(String line, List<StagedChange> result) {
        try {
            StringScanner s = new StringScanner(line);
            String type = s.spaceToken();
            if (STATUS_ORDINARY.equals(type)) {
                parseOrdinary(s, line, result);
            }
            else if (STATUS_UNMERGED.equals(type)) {
                parseUnmerged(s, result);
            }
            else if (LOG.isDebugEnabled()) {
                LOG.debug("Unknown status line: " + line);
            }
        }
        catch (VcsException e) {
            LOG.error(e);
        }
    }

    private void parseUnmerged(StringScanner s, List<StagedChange> result) throws VcsException {
        s.spaceToken(); // <XY> status
        boolean isSubmodule = !SUBMODULE_NONE.equals(s.spaceToken()); // <sub>
        s.spaceToken(); // <m1> file mode
        s.spaceToken(); // <m2> file mode
        s.spaceToken(); // <m3> file mode
        s.spaceToken(); // <mW> file mode
        s.spaceToken(); // <h1> blob blob
        s.spaceToken(); // <h2> blob hash
        s.spaceToken(); // <h3> blob hash
        String filePath = s.line();

        FilePath path = VcsUtil.getFilePath(myRoot, GitUtil.unescapePath(filePath));
        result.add(new StagedChange.Unmerged(path, isSubmodule));
    }

    private void parseOrdinary(StringScanner s, String line, List<StagedChange> result) throws VcsException {
        String status = s.spaceToken(); // <XY> status
        boolean isSubmodule = !SUBMODULE_NONE.equals(s.spaceToken()); // <sub>
        boolean headIsExecutable = GitIndexUtil.EXECUTABLE_MODE.equals(s.spaceToken()); // <mH> file mode
        boolean stagedIsExecutable = GitIndexUtil.EXECUTABLE_MODE.equals(s.spaceToken()); // <mI> file mode
        s.spaceToken(); // <mW> file mode
        Hash headHash = HashImpl.build(s.spaceToken()); // <hH> blob hash
        Hash stagedHash = HashImpl.build(s.spaceToken()); // <hI> blob hash
        String filePath = s.line();

        if (status.length() != 2) {
            LOG.warn("Unknown file status: " + line);
            return;
        }

        char stagedStatus = status.charAt(0);
        boolean isFileStaged = stagedStatus != '.';
        if (isFileStaged) {
            FilePath path = VcsUtil.getFilePath(myRoot, GitUtil.unescapePath(filePath));
            result.add(new StagedChange.Staged(path, isSubmodule, headHash, stagedHash, headIsExecutable, stagedIsExecutable));
        }
    }

    private void resetExcluded() {
        List<String> updateIndexLines = new ArrayList<>();
        for (StagedChange.Staged change : mySavedStagedChanges) {
            String line = createUpdateIndexLine(change, true);
            if (line != null) {
                updateIndexLines.add(line);
            }
        }
        if (!updateIndexLines.isEmpty()) {
            try {
                LOG.debug("Resetting staged changes after commit");
                GitLineHandler handler = new GitLineHandler(myProject, myRoot, GitCommand.UPDATE_INDEX);
                handler.addParameters("--index-info");
                handler.setInputProcessor(GitHandlerInputProcessorUtil.writeLines(updateIndexLines, handler.getCharset()));
                Git.getInstance().runCommand(handler).throwOnError();
            }
            catch (VcsException e) {
                LOG.warn(e);
            }
        }
    }

    private @Nullable String createUpdateIndexLine(StagedChange.Staged change, boolean toHead) {
        String relativePath = VcsFileUtil.relativePath(myRoot, change.getFilePath());
        if (relativePath == null) {
            return null;
        }

        Hash hash = toHead ? change.getHeadHash() : change.getStagedHash();
        boolean isExecutable = toHead ? change.isHeadIsExecutable() : change.isStagedIsExecutable();

        // zeroed-out hash means "delete from index" for update-index --index-info
        String hashString = hash.asString();
        if (GitIndexUtil.isNullHash(hashString)) {
            return "0 " + hashString + "\t" + relativePath;
        }

        String mode = resolveMode(change.isSubmodule(), isExecutable);
        return formatIndexInfo(mode, hashString, relativePath);
    }

    private static String resolveMode(boolean isSubmodule, boolean isExecutable) {
        if (isSubmodule) {
            return GitIndexUtil.SUBMODULE_MODE;
        }
        return isExecutable ? GitIndexUtil.EXECUTABLE_MODE : GitIndexUtil.DEFAULT_MODE;
    }

    private static String formatIndexInfo(String mode, String hash, String relativePath) {
        // format: "<mode> <hash> <stage>\t<path>"
        return mode + " " + hash + " 0\t" + relativePath;
    }

    private static String getStagedLogString(String rootPath, Collection<? extends StagedChange> changes) {
        return changes.stream()
            .map(it -> {
                String relativePath = FileUtil.getRelativePath(rootPath, it.getFilePath().getPath(), '/');
                return relativePath == null ? it.getFilePath().getPath() : relativePath;
            })
            .collect(Collectors.joining(", "));
    }

    private abstract static sealed class StagedChange {
        private final FilePath myFilePath;
        private final boolean myIsSubmodule;

        private StagedChange(FilePath filePath, boolean isSubmodule) {
            myFilePath = filePath;
            myIsSubmodule = isSubmodule;
        }

        FilePath getFilePath() {
            return myFilePath;
        }

        boolean isSubmodule() {
            return myIsSubmodule;
        }

        private static final class Unmerged extends StagedChange {
            private Unmerged(FilePath filePath, boolean isSubmodule) {
                super(filePath, isSubmodule);
            }
        }

        private static final class Staged extends StagedChange {
            private final Hash myHeadHash;
            private final Hash myStagedHash;
            private final boolean myHeadIsExecutable;
            private final boolean myStagedIsExecutable;

            private Staged(FilePath filePath,
                           boolean isSubmodule,
                           Hash headHash,
                           Hash stagedHash,
                           boolean headIsExecutable,
                           boolean stagedIsExecutable) {
                super(filePath, isSubmodule);
                myHeadHash = headHash;
                myStagedHash = stagedHash;
                myHeadIsExecutable = headIsExecutable;
                myStagedIsExecutable = stagedIsExecutable;
            }

            Hash getHeadHash() {
                return myHeadHash;
            }

            Hash getStagedHash() {
                return myStagedHash;
            }

            boolean isHeadIsExecutable() {
                return myHeadIsExecutable;
            }

            boolean isStagedIsExecutable() {
                return myStagedIsExecutable;
            }
        }
    }
}
