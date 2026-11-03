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
package git4idea.push;

import consulo.localize.LocalizeValue;
import consulo.versionControlSystem.distributed.push.ForcePushType;
import git4idea.repo.GitRepository;

import java.util.Map;

/**
 * {@link GitForcePushType#FORCE_WITH_LEASE_HASH} with expected hashes of target branches, entered by user
 */
public record GitForcePushWithHash(Map<GitRepository, String> expectedHashes) implements ForcePushType {
    @Override
    public LocalizeValue getActionText() {
        return GitForcePushType.FORCE_WITH_LEASE_HASH.getActionText();
    }
}
