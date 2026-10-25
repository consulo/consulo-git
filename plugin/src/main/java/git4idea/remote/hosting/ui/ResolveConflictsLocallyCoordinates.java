// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote.hosting.ui;

import git4idea.remote.hosting.HostedGitRepositoryRemote;

/**
 * Represents a request to resolve conflicts in a code review locally.
 */
public record ResolveConflictsLocallyCoordinates(HostedGitRepositoryRemote headRemoteDescriptor,
                                                 String headRefName,
                                                 HostedGitRepositoryRemote baseRemoteDescriptor,
                                                 String baseRefName) {
}
