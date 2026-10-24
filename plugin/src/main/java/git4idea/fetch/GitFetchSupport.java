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
package git4idea.fetch;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.project.Project;
import consulo.util.lang.Pair;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

@ServiceAPI(ComponentScope.PROJECT)
public interface GitFetchSupport {
    /**
     * For each given repository, fetches the "default" remote.
     * The latter is identified by {@link #getDefaultRemoteToFetch}.
     */
    GitFetchResult fetchDefaultRemote(Collection<GitRepository> repositories);

    /**
     * For each given repository, fetches all its remotes.
     */
    GitFetchResult fetchAllRemotes(Collection<GitRepository> repositories);

    /**
     * Fetches the given remote.
     */
    GitFetchResult fetch(GitRepository repository, GitRemote remote);

    GitFetchResult fetchUnshallow(GitRepository repository, GitRemote remote);

    /**
     * Fetches the given remotes.
     */
    GitFetchResult fetchRemotes(Collection<Pair<GitRepository, GitRemote>> remotes);

    /**
     * Fetches the given remote using provided refspec.
     */
    GitFetchResult fetch(GitRepository repository, GitRemote remote, String refspec);

    /**
     * Fetches all targets defined in {@code fetchSpec}
     */
    GitFetchResult fetch(Collection<GitFetchSpec> fetchSpec);

    /**
     * Returns the default remote to fetch from, or null if there are no remotes in the repository,
     * or if it is impossible to guess which remote is default.
     */
    @Nullable
    GitRemote getDefaultRemoteToFetch(GitRepository repository);

    /**
     * @return true if there's an active fetch process, false otherwise
     */
    boolean isFetchRunning();

    static GitFetchSupport fetchSupport(Project project) {
        return project.getInstance(GitFetchSupport.class);
    }
}
