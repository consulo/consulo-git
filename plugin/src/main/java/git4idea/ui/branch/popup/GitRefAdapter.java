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
package git4idea.ui.branch.popup;

import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsRef;
import git4idea.GitReference;

/**
 * {@link DvcsRef} adapter over a real {@link GitReference} (local/remote branch or tag).
 * <p>
 * Equality is delegated to {@link GitReference}, which already compares by full name, so adapters
 * work correctly as tree-model keys and in favorite lookups.
 *
 * @author VISTALL
 */
public record GitRefAdapter(GitReference reference) implements DvcsRef {
    @Override
    public String getName() {
        return reference.getName();
    }

    @Override
    public String getFullName() {
        return reference.getFullName();
    }
}
