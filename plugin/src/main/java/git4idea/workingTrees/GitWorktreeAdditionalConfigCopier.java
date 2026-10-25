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
package git4idea.workingTrees;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.application.Application;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Copies extra local files into a new worktree, as part of "Copy local project setup" in the New Worktree
 * dialog. Register an implementation to copy a file this plugin does not already know about.
 * <p>
 * The dialog shows one checkbox per registered extension, labeled with {@link #getCheckboxText()}. {@link #copyAdditionalConfig}
 * runs only if the user leaves that checkbox checked.
 */
@ExtensionAPI(ComponentScope.APPLICATION)
public interface GitWorktreeAdditionalConfigCopier {
    /**
     * Every registered {@link GitWorktreeAdditionalConfigCopier} extension.
     */
    static List<GitWorktreeAdditionalConfigCopier> getExtensions() {
        List<GitWorktreeAdditionalConfigCopier> result = new ArrayList<>();
        Application.get().getExtensionPoint(GitWorktreeAdditionalConfigCopier.class).forEach(result::add);
        return result;
    }

    /**
     * Runs every extension in {@code enabled}. Returns {@code true} only when every one of them succeeded.
     */
    static boolean copyAll(GitWorktreeConfigCopyContext context, Set<GitWorktreeAdditionalConfigCopier> enabled) {
        boolean allSucceeded = true;
        for (GitWorktreeAdditionalConfigCopier extension : getExtensions()) {
            if (!enabled.contains(extension)) {
                continue;
            }
            try {
                extension.copyAdditionalConfig(context).join();
            }
            catch (Throwable e) {
                Logger.getInstance(GitWorktreeAdditionalConfigCopier.class)
                    .error(extension.getClass().getName() + " failed to copy additional worktree config", e);
                allSucceeded = false;
            }
        }
        return allSucceeded;
    }

    /**
     * The label of this extension's checkbox in the New Worktree dialog.
     */
    LocalizeValue getCheckboxText();

    CompletableFuture<?> copyAdditionalConfig(GitWorktreeConfigCopyContext context);
}
