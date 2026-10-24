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
package git4idea;

import org.jspecify.annotations.Nullable;

public class GitStandardLocalBranch extends GitBranch {
    public GitStandardLocalBranch(String name) {
        super(name);
    }

    @Override
    public boolean isRemote() {
        return false;
    }

    @Override
    public String getFullName() {
        return REFS_HEADS_PREFIX + myName;
    }

    @Override
    public int compareTo(@Nullable GitReference o) {
        if (o instanceof GitStandardLocalBranch branch) {
            // optimization: do not build getFullName
            return REFS_NAMES_COMPARATOR.compare(myName, branch.myName);
        }
        return super.compareTo(o);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GitStandardLocalBranch branch)) {
            return false;
        }
        return BRANCH_NAME_HASHING_STRATEGY.equals(myName, branch.myName);
    }

    @Override
    public int hashCode() {
        return BRANCH_NAME_HASHING_STRATEGY.hashCode(myName);
    }
}
