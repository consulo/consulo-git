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
package git4idea.commands;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.component.ProcessCanceledException;
import consulo.disposer.Disposable;
import consulo.externalProcessAuthHelper.AuthenticationGate;
import consulo.externalProcessAuthHelper.AuthenticationMode;
import consulo.externalProcessAuthHelper.ExternalProcessHandlerService;
import consulo.project.Project;
import consulo.util.lang.StringUtil;
import git4idea.http.GitAskPassApp;
import git4idea.http.GitAskPassAppHandler;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;

/**
 * Provides the authentication mechanism for Git HTTP connections.
 */
@ServiceAPI(ComponentScope.APPLICATION)
public abstract class GitHttpAuthService extends ExternalProcessHandlerService<GitAskPassAppHandler> {
    protected GitHttpAuthService() {
        super("intellij-git-askpass", GitAskPassApp.class);
    }

    /**
     * Creates new {@link GitHttpAuthenticator} that will be requested to handle username and password requests from Git.
     */
    public abstract GitHttpAuthenticator createAuthenticator(
        Project project,
        Collection<String> urls,
        Path workingDirectory,
        AuthenticationGate authenticationGate,
        AuthenticationMode authenticationMode
    );

    @Override
    protected @Nullable String handleRequest(GitAskPassAppHandler handler, String requestBody) {
        return handler.handleInput(requestBody);
    }

    public UUID registerHandler(GitHttpAuthenticator authenticator, Disposable disposable) {
        return registerHandler(new AuthAppHandler(authenticator), disposable);
    }

    private static class AuthAppHandler implements GitAskPassAppHandler {
        private final GitHttpAuthenticator myAuthenticator;

        AuthAppHandler(GitHttpAuthenticator authenticator) {
            myAuthenticator = authenticator;
        }

        @Override
        public @Nullable String handleInput(String arg) {
            boolean usernameNeeded = StringUtil.startsWithIgnoreCase(arg, "username");
            String[] split = arg.split(" ");
            String url = split.length > 2 ? parseUrl(split[2]) : "";
            try {
                return usernameNeeded ? myAuthenticator.askUsername(url) : myAuthenticator.askPassword(url);
            }
            catch (ProcessCanceledException e) {
                return null;
            }
        }

        private static String parseUrl(String url) {
            // un-quote and remove the trailing colon
            int start = 0;
            while (start < url.length() && url.charAt(start) == '\'') {
                start++;
            }
            int end = url.length();
            while (end > start && (url.charAt(end - 1) == '\'' || url.charAt(end - 1) == ':')) {
                end--;
            }
            return url.substring(start, end);
        }
    }
}
