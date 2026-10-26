// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.remote;

import consulo.credentialStorage.AuthData;
import consulo.project.Project;
import consulo.ui.Component;
import consulo.ui.annotation.RequiredUIAccess;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Provides authentication information to the {@link git4idea.commands.GitHttpAuthenticator} for a single run.
 * Main use-case is to show a button in git login dialog which will allow user to manually select data that will be used for the current run.
 * Generally it will be shown when {@link GitHttpAuthDataProvider} could not provide appropriate auth data silently.
 * <p>
 * It is advised to configure related {@link GitHttpAuthDataProvider} for the continuous use when data is selected.
 *
 * @see GitRepositoryHostingService#getInteractiveAuthDataProvider(Project, String)
 * @see GitRepositoryHostingService#getInteractiveAuthDataProvider(Project, String, String)
 */
public interface InteractiveGitHttpAuthDataProvider {
    @RequiredUIAccess
    @Nullable AuthData getAuthData(@Nullable Component parentComponent);

    @RequiredUIAccess
    default CompletableFuture<@Nullable AuthData> getAuthDataAsync(@Nullable Component parentComponent) {
        return CompletableFuture.completedFuture(getAuthData(parentComponent));
    }
}
