// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
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

import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.log.base.VcsFileStatusInfo;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class DefaultGitLogFullRecordBuilder implements GitLogRecordBuilder<GitLogFullRecord> {
    private List<VcsFileStatusInfo> myStatuses = new ArrayList<>();

    List<VcsFileStatusInfo> getStatuses() {
        return myStatuses;
    }

    @Override
    public GitLogFullRecord build(Map<GitLogParser.GitLogOption, String> options, boolean supportsRawBody) {
        return new GitLogFullRecord(options, myStatuses, supportsRawBody);
    }

    @Override
    public void addPath(Change.Type type, String firstPath, @Nullable String secondPath) {
        myStatuses.add(new VcsFileStatusInfo(type, firstPath, secondPath));
    }

    @Override
    public void clear() {
        myStatuses = new ArrayList<>();
    }
}
