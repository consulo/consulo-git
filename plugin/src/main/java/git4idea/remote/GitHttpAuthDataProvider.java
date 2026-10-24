/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package git4idea.remote;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.credentialStorage.AuthData;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import git4idea.commands.GitHttpAuthenticator;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Provides authentication information to the {@link GitHttpAuthenticator} on attempt to connect an HTTP remote.
 * Useful for reusing Github credentials stored in the settings to connect the github remote (IDEA-87530).
 * <p>
 * {@link AuthData} with null password will be ignored
 */
@ExtensionAPI(ComponentScope.APPLICATION)
public interface GitHttpAuthDataProvider {
    default @Nullable AuthData getAuthData(Project project, String url, String login) {
        return getAuthData(project, url);
    }

    default @Nullable AuthData getAuthData(Project project, String url) {
        return null;
    }

    default void forgetPassword(Project project, String url, AuthData authData) {
    }

    /**
     * @return true  - if provider does not show any prompts except internal password storage access,
     * such provider can be interrogated by GitHttpAuthenticator with {@link consulo.externalProcessAuthHelper.AuthenticationMode#SILENT} mode
     */
    default boolean isSilent() {
        return false;
    }

    /**
     * Returns a presentable error message for an authentication failure handled by this provider.
     *
     * @param errorOutput raw error output of the Git operation
     * @return a provider-specific authentication failure message, or empty
     */
    default LocalizeValue getPresentableErrorMessage(List<String> errorOutput) {
        return LocalizeValue.empty();
    }
}
