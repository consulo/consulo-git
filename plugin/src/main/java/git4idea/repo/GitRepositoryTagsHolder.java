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
package git4idea.repo;

import consulo.versionControlSystem.log.base.HashImpl;
import git4idea.GitTag;

import java.util.List;
import java.util.Set;

public interface GitRepositoryTagsHolder {
    /**
     * Contains the current state of repository tags.
     */
    GitRepositoryTagsState getState();

    void scheduleReload();

    default List<GitTag> getTagsForCommit(String hash) {
        List<GitTag> tags = getState().getCommitHashesToTags().get(HashImpl.build(hash));
        return tags == null ? List.of() : tags;
    }

    default Set<GitTag> getTags() {
        return getState().getTagsToCommitHashes().keySet();
    }
}
