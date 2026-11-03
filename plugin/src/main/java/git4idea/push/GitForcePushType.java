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

import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.versionControlSystem.distributed.push.ForcePushType;
import git4idea.config.GitVersion;
import git4idea.config.GitVersionSpecialty;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Stored in settings by name - do not rename or remove constants
 */
public enum GitForcePushType implements ForcePushType {
    /**
     * --force
     */
    FORCE(null),
    /**
     * --force-with-lease
     */
    FORCE_WITH_LEASE(GitVersionSpecialty.SUPPORTS_FORCE_PUSH_WITH_LEASE),
    /**
     * --force-with-lease --force-if-includes
     */
    FORCE_WITH_LEASE_IF_INCLUDES(GitVersionSpecialty.SUPPORTS_FORCE_IF_INCLUDES),
    /**
     * --force-with-lease=refname:hash, hash is asked from user
     */
    FORCE_WITH_LEASE_HASH(GitVersionSpecialty.SUPPORTS_FORCE_PUSH_WITH_LEASE);

    public static final List<GitForcePushType> DEFAULTS = List.of(FORCE_WITH_LEASE, FORCE_WITH_LEASE_IF_INCLUDES, FORCE_WITH_LEASE_HASH);

    private final @Nullable GitVersionSpecialty myRequiredSpecialty;

    GitForcePushType(@Nullable GitVersionSpecialty requiredSpecialty) {
        myRequiredSpecialty = requiredSpecialty;
    }

    public boolean isSupported(GitVersion version) {
        return myRequiredSpecialty == null || myRequiredSpecialty.existsIn(version);
    }

    /**
     * @return minimal git version, which supports this type, or null if supported by any version
     */
    public @Nullable String getRequiredVersion() {
        return switch (this) {
            case FORCE -> null;
            case FORCE_WITH_LEASE, FORCE_WITH_LEASE_HASH -> "1.8.5";
            case FORCE_WITH_LEASE_IF_INCLUDES -> "2.30";
        };
    }

    @Override
    public LocalizeValue getActionText() {
        return switch (this) {
            case FORCE -> GitLocalize.pushForceTypeForce();
            case FORCE_WITH_LEASE -> GitLocalize.pushForceTypeForceWithLease();
            case FORCE_WITH_LEASE_IF_INCLUDES -> GitLocalize.pushForceTypeForceWithLeaseIfIncludes();
            case FORCE_WITH_LEASE_HASH -> GitLocalize.pushForceTypeForceWithLeaseHash();
        };
    }

    @Override
    public LocalizeValue getDescription() {
        return switch (this) {
            case FORCE -> GitLocalize.pushForceTypeForceDescription();
            case FORCE_WITH_LEASE -> GitLocalize.pushForceTypeForceWithLeaseDescription();
            case FORCE_WITH_LEASE_IF_INCLUDES -> GitLocalize.pushForceTypeForceWithLeaseIfIncludesDescription();
            case FORCE_WITH_LEASE_HASH -> GitLocalize.pushForceTypeForceWithLeaseHashDescription();
        };
    }
}
