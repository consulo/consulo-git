/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package git4idea.push;

import consulo.localHistory.Label;
import consulo.versionControlSystem.update.UpdatedFiles;
import git4idea.repo.GitRepository;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Combined push result for all affected repositories in the project.
 */
class GitPushResult {
  @Nonnull
  private final Map<GitRepository, GitPushRepoResult> myResults;
  @Nonnull
  private final UpdatedFiles myUpdatedFiles;
  @Nullable
  private final Label myBeforeUpdateLabel;
  @Nullable
  private final Label myAfterUpdateLabel;

  GitPushResult(@Nonnull Map<GitRepository, GitPushRepoResult> results,
                @Nonnull UpdatedFiles files,
                @Nullable Label beforeUpdateLabel,
                @Nullable Label afterUpdateLabel) {
    myResults = results;
    myUpdatedFiles = files;
    myBeforeUpdateLabel = beforeUpdateLabel;
    myAfterUpdateLabel = afterUpdateLabel;
  }

  @Nonnull
  public Map<GitRepository, GitPushRepoResult> getResults() {
    return myResults;
  }

  @Nonnull
  public UpdatedFiles getUpdatedFiles() {
    return myUpdatedFiles;
  }

  @Nullable
  public Label getBeforeUpdateLabel() {
    return myBeforeUpdateLabel;
  }

  @Nullable
  public Label getAfterUpdateLabel() {
    return myAfterUpdateLabel;
  }
}
