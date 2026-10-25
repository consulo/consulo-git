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
package git4idea.branch;

public enum CheckoutInOtherWorktreeButtonSet {
    /**
     * Checkout Anyway / Open Existing Worktree / Cancel.
     */
    PROCEED_OPEN_EXISTING_OR_CANCEL,

    /**
     * Checkout Anyway / Open Existing Worktree — used before the git command has even been attempted.
     */
    PROCEED_OR_OPEN_EXISTING,

    /**
     * Checkout Anyway / Cancel — "open existing" was already offered earlier in the same user flow.
     */
    PROCEED_OR_CANCEL
}
