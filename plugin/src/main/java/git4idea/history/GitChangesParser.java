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
 */package git4idea.history;

import consulo.logging.Logger;
import consulo.project.Project;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ContentRevision;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.log.base.VcsFileStatusInfo;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.status.FileStatus;
import git4idea.GitContentRevision;
import git4idea.GitRevisionNumber;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class GitChangesParser {
    private static final Logger LOG = Logger.getInstance(GitChangesParser.class);

    public static List<Change> parse(Project project,
                                     VirtualFile root,
                                     List<VcsFileStatusInfo> statusInfos,
                                     String hash,
                                     Date date,
                                     @Nullable String parentsHash) {
        GitRevisionNumber thisRevision = new GitRevisionNumber(hash, date);
        GitRevisionNumber parentRevision = parentsHash == null ? null : new GitRevisionNumber(parentsHash);

        List<Change> result = new ArrayList<>();
        for (VcsFileStatusInfo statusInfo : statusInfos) {
            result.add(parseChange(project, root, thisRevision, parentRevision, statusInfo));
        }
        return result;
    }

    private static Change parseChange(Project project,
                                      VirtualFile vcsRoot,
                                      VcsRevisionNumber thisRevision,
                                      @Nullable VcsRevisionNumber parentRevision,
                                      VcsFileStatusInfo statusInfo) {
        final ContentRevision before;
        final ContentRevision after;
        final String path = statusInfo.getFirstPath();
        final FilePath filePath = GitContentRevision.createPath(vcsRoot, path);

        FileStatus status;
        switch (statusInfo.getType()) {
            case NEW -> {
                before = null;
                status = FileStatus.ADDED;
                after = GitContentRevision.createRevision(filePath, thisRevision, project);
            }
            case MODIFICATION -> {
                status = FileStatus.MODIFIED;
                before = GitContentRevision.createRevision(filePath, parentRevision, project);
                after = GitContentRevision.createRevision(filePath, thisRevision, project);
            }
            case DELETED -> {
                status = FileStatus.DELETED;
                before = GitContentRevision.createRevision(filePath, parentRevision, project);
                after = null;
            }
            case MOVED -> {
                status = FileStatus.MODIFIED;
                String secondPath = statusInfo.getSecondPath();
                final FilePath filePathAfterRename = secondPath == null ? filePath : GitContentRevision.createPath(vcsRoot, secondPath);
                before = GitContentRevision.createRevision(filePath, parentRevision, project);
                after = GitContentRevision.createRevision(filePathAfterRename, thisRevision, project);
            }
            default -> throw new AssertionError("Unknown file status: " + statusInfo);
        }
        return new Change(before, after, status);
    }

    static Change.Type getChangeType(GitChangeType type) {
        return switch (type) {
            case ADDED -> Change.Type.NEW;
            case TYPE_CHANGED, MODIFIED -> Change.Type.MODIFICATION;
            case DELETED -> Change.Type.DELETED;
            case COPIED, RENAMED -> Change.Type.MOVED;
            default -> {
                LOG.error("Unknown git change type: " + type);
                yield Change.Type.MODIFICATION;
            }
        };
    }
}
