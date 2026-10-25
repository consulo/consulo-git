// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
 */package git4idea;

import consulo.project.Project;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangesUtil;
import consulo.versionControlSystem.log.Hash;
import consulo.versionControlSystem.log.VcsShortCommitDetails;
import consulo.versionControlSystem.log.VcsUser;
import consulo.versionControlSystem.log.base.VcsChangesLazilyParsedDetails;
import consulo.versionControlSystem.log.base.VcsChangesLazilyParsedDetailsChanges;
import consulo.versionControlSystem.log.base.VcsChangesLazilyParsedDetailsChangesParser;
import consulo.versionControlSystem.log.base.VcsChangesLazilyParsedDetailsUnparsedChanges;
import consulo.versionControlSystem.log.base.VcsFileStatusInfo;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.history.GitCommitRequirements;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Represents a Git commit with its meta information (hash, author, message, etc.), its parents and the {@link Change changes}.
 */
public final class GitCommit extends VcsChangesLazilyParsedDetails {
    private final GitCommitRequirements myRequirements;

    public GitCommit(Project project, Hash hash, List<Hash> parents, long commitTime, VirtualFile root,
                     String subject, VcsUser author, String message, VcsUser committer,
                     long authorTime,
                     List<List<VcsFileStatusInfo>> reportedChanges,
                     GitCommitRequirements requirements) {
        super(project, hash, parents, commitTime, root, subject, author, message, committer, authorTime, reportedChanges,
            new GitChangesParser());
        myRequirements = requirements;
    }

    public Set<FilePath> getAffectedPaths() {
        VcsChangesLazilyParsedDetailsChanges changesObject = getChangesObject();
        if (changesObject instanceof VcsChangesLazilyParsedDetailsUnparsedChanges unparsedChanges) {
            Set<FilePath> result = new HashSet<>();

            for (VcsFileStatusInfo statusInfo : unparsedChanges.getMergedStatuses()) {
                result.add(GitContentRevision.createPath(getRoot(), statusInfo.getFirstPath()));

                String secondPath = statusInfo.getSecondPath();
                if (secondPath != null) {
                    result.add(GitContentRevision.createPath(getRoot(), secondPath));
                }
            }

            return result;
        }

        Set<FilePath> result = new HashSet<>();
        for (Change change : getChanges()) {
            FilePath beforePath = ChangesUtil.getBeforePath(change);
            if (beforePath != null) {
                result.add(beforePath);
            }
            FilePath afterPath = ChangesUtil.getAfterPath(change);
            if (afterPath != null) {
                result.add(afterPath);
            }
        }
        return result;
    }

    public GitCommitRequirements getRequirements() {
        return myRequirements;
    }

    private static class GitChangesParser implements VcsChangesLazilyParsedDetailsChangesParser {
        @Override
        public List<Change> parseStatusInfo(Project project,
                                            VcsShortCommitDetails commit,
                                            List<VcsFileStatusInfo> changes,
                                            int parentIndex) {
            String parentHash = null;
            if (parentIndex < commit.getParents().size()) {
                parentHash = commit.getParents().get(parentIndex).asString();
            }
            return git4idea.history.GitChangesParser.parse(project, commit.getRoot(), changes, commit.getId().asString(),
                new Date(commit.getCommitTime()), parentHash);
        }
    }
}
