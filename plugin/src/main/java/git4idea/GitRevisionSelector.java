/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package git4idea;

import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.diff.RevisionSelector;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Git revision selector class.
 */
public class GitRevisionSelector implements RevisionSelector {
  /**
   * {@inheritDoc}
   */
  @Nullable
  public VcsRevisionNumber selectNumber(@Nonnull VirtualFile file) {
    //GitVirtualFile gitFile = (GitVirtualFile) file;
    //TODO: implement selectNumber()
    return null;
  }
}
