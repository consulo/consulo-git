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

import consulo.application.util.SystemInfo;
import consulo.util.collection.HashingStrategy;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.util.FilePathHashingStrategy;
import jakarta.annotation.Nonnull;

/**
 * The base class for named git references, like branches and tags.
 */
public abstract class GitReference implements Comparable<GitReference> {

  public static final HashingStrategy<String> BRANCH_NAME_HASHING_STRATEGY = FilePathHashingStrategy.create();

  @Nonnull
  protected final String myName;

  public GitReference(@Nonnull String name) {
    myName = new String(name);
  }

  /**
   * @return the name of the reference, e.g. "origin/master" or "feature".
   * @see #getFullName()
   */
  @Nonnull
  public String getName() {
    return myName;
  }

  /**
   * @return the full name of the reference, e.g. "refs/remotes/origin/master" or "refs/heads/master".
   */
  @Nonnull
  public abstract String getFullName();

  @Override
  public String toString() {
    return getFullName();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    GitReference reference = (GitReference)o;
    return BRANCH_NAME_HASHING_STRATEGY.equals(myName, reference.myName);
  }

  @Override
  public int hashCode() {
    return BRANCH_NAME_HASHING_STRATEGY.hashCode(myName);
  }

  public int compareTo(GitReference o) {
    return o == null ? 1 : StringUtil.compare(getFullName(), o.getFullName(), SystemInfo.isFileSystemCaseSensitive);
  }
}
