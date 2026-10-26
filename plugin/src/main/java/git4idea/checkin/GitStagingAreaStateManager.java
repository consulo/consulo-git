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

import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import git4idea.repo.GitRepository;

import java.util.Set;

/**
 * Prepares the staging area for a commit and restores it after
 * It is intended to be used only once
 */
public interface GitStagingAreaStateManager extends AutoCloseable {
    /**
     * Takes which paths are intended to be added to the staging area and which to
     * be removed for the commit. Doesn't stage or unstage them,
     * only excludes all other paths from the staging area
     */
    void prepareStagingArea(Set<FilePath> toAdd, Set<FilePath> toRemove) throws VcsException;

    /**
     * Rollback the changes done by {@link #prepareStagingArea}
     */
    void restore();

    @Override
    default void close() {
        restore();
    }

    static GitStagingAreaStateManager create(GitRepository repository) {
        return new GitIndexInfoStagingAreaStateManager(repository);
    }
}
