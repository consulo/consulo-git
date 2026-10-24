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

import consulo.externalProcessAuthHelper.AuthenticationMode;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public record GitFetchSpec(
    GitRepository repository,
    GitRemote remote,
    @Nullable String refspec,
    boolean unshallow,
    @Nullable AuthenticationMode authMode
) {
    private static final String NO_RECURSE_SUBMODULES = "--recurse-submodules=no";
    private static final String UNSHALLOW = "--unshallow";

    public GitFetchSpec(GitRepository repository, GitRemote remote) {
        this(repository, remote, null, false, null);
    }

    public GitFetchSpec(GitRepository repository, GitRemote remote, @Nullable String refspec) {
        this(repository, remote, refspec, false, null);
    }

    public GitFetchSpec(GitRepository repository, GitRemote remote, AuthenticationMode authMode) {
        this(repository, remote, null, false, authMode);
    }

    public String[] asParams() {
        List<String> params = new ArrayList<>();
        if (refspec != null) {
            params.add(refspec);
        }
        params.add(NO_RECURSE_SUBMODULES);
        if (unshallow) {
            params.add(UNSHALLOW);
        }
        return params.toArray(String[]::new);
    }
}
