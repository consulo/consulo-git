// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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

import consulo.annotation.component.ExtensionImpl;
import consulo.application.progress.ProgressIndicator;
import consulo.project.Project;
import git4idea.fetch.GitFetchHandler;

import java.util.List;
import java.util.Map;

@ExtensionImpl
public class GitRepositoryTagsHolderFetchHandler implements GitFetchHandler {
    @Override
    public void doAfterSuccessfulFetch(Project project, Map<GitRepository, List<GitRemote>> fetches, ProgressIndicator indicator) {
        fetches.keySet().forEach(repository -> repository.getTagsHolder().scheduleReload());
    }
}
