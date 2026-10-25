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
 */package git4idea.history;

import consulo.project.Project;
import consulo.versionControlSystem.log.Hash;
import consulo.versionControlSystem.log.VcsLogObjectsFactory;
import consulo.versionControlSystem.log.base.HashImpl;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitCommit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

class GitFullDetailsCollector extends GitDetailsCollector<GitLogFullRecord, GitCommit> {
    GitFullDetailsCollector(Project project, VirtualFile root, GitLogRecordBuilder<GitLogFullRecord> recordBuilder) {
        super(project, root, recordBuilder);
    }

    GitFullDetailsCollector(Project project, VirtualFile root) {
        this(project, root, new DefaultGitLogFullRecordBuilder());
    }

    @Override
    protected GitCommit createCommit(List<GitLogFullRecord> records, VcsLogObjectsFactory factory, GitCommitRequirements requirements) {
        GitLogFullRecord record = records.getLast();
        List<Hash> parents = new ArrayList<>();
        for (String parent : record.getParentsHashes()) {
            parents.add(factory.createHash(parent));
        }

        return new GitCommit(myProject, HashImpl.build(record.getHash()), parents, record.getCommitTime(), myRoot, record.getSubject(),
            factory.createUser(record.getAuthorName(), record.getAuthorEmail()), record.getFullMessage(),
            factory.createUser(record.getCommitterName(), record.getCommitterEmail()), record.getAuthorTimeStamp(),
            records.stream().map(GitLogFullRecord::getStatusInfos).toList(),
            requirements
        );
    }

    @Override
    protected GitLogRecordCollector<GitLogFullRecord> createRecordsCollector(Consumer<List<GitLogFullRecord>> consumer) {
        return new GitLogRecordCollector<>(myProject, myRoot, consumer) {
            @Override
            protected GitLogFullRecord createEmptyCopy(GitLogFullRecord r) {
                return new GitLogFullRecord(r.getOptions(), List.of(), r.isSupportsRawBody());
            }
        };
    }
}
