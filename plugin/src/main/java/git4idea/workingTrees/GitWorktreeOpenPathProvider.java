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
import consulo.application.dumb.IndexNotReadyException;
import consulo.logging.Logger;
import consulo.project.Project;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@ExtensionAPI(ComponentScope.APPLICATION)
public interface GitWorktreeOpenPathProvider {
    /**
     * Returns an alternate path that should be opened for the given worktree.
     * Returning {@code null} lets Git4Idea fall back to the worktree root.
     */
    CompletableFuture<@Nullable Path> getPathToOpen(Project project, Path worktreePath);

    static CompletableFuture<Path> resolveWorktreeOpenPath(Project project, Path worktreePath) {
        List<GitWorktreeOpenPathProvider> providers = new ArrayList<>();
        project.getApplication().getExtensionPoint(GitWorktreeOpenPathProvider.class).forEach(providers::add);
        return resolve(project, worktreePath, providers.iterator());
    }

    private static CompletableFuture<Path> resolve(Project project, Path worktreePath, Iterator<GitWorktreeOpenPathProvider> providers) {
        if (!providers.hasNext()) {
            return CompletableFuture.completedFuture(worktreePath);
        }
        GitWorktreeOpenPathProvider provider = providers.next();
        CompletableFuture<@Nullable Path> future;
        try {
            future = provider.getPathToOpen(project, worktreePath);
        }
        catch (IndexNotReadyException e) {
            return resolve(project, worktreePath, providers);
        }
        catch (Throwable e) {
            Logger.getInstance(GitWorktreeOpenPathProvider.class).error(e);
            return resolve(project, worktreePath, providers);
        }
        return future.handle((path, error) -> {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            if (cause != null && !(cause instanceof IndexNotReadyException)) {
                Logger.getInstance(GitWorktreeOpenPathProvider.class).error(cause);
            }
            return path;
        }).thenCompose(path -> path != null ? CompletableFuture.completedFuture(path) : resolve(project, worktreePath, providers));
    }
}
