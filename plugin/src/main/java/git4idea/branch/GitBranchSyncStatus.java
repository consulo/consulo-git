// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.branch;

/**
 * State of synchronization between two branches from a viewpoint of the first branch
 *
 * @param incoming other branch contains commits that are not present in the first branch
 * @param outgoing first branch contains commits that are not present in the other branch
 */
public record GitBranchSyncStatus(boolean incoming, boolean outgoing) {
    public static final GitBranchSyncStatus SYNCED = new GitBranchSyncStatus(false, false);
}
