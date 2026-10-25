// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.project.Project;
import consulo.versionControlSystem.distributed.hosting.RepositoryHostingService;
import org.jspecify.annotations.Nullable;

@ExtensionAPI(ComponentScope.APPLICATION)
public abstract class GitRepositoryHostingService implements RepositoryHostingService {
    /**
     * @see InteractiveGitHttpAuthDataProvider
     */
    public @Nullable InteractiveGitHttpAuthDataProvider getInteractiveAuthDataProvider(Project project, String url) {
        return null;
    }

    /**
     * @see InteractiveGitHttpAuthDataProvider
     */
    public @Nullable InteractiveGitHttpAuthDataProvider getInteractiveAuthDataProvider(Project project, String url, String login) {
        return null;
    }
}
