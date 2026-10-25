// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.remote.hosting;

import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.patch.PatchReader;
import consulo.versionControlSystem.change.patch.PatchSyntaxException;
import consulo.versionControlSystem.change.patch.TextFilePatch;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitHandlerInputProcessorUtil;
import git4idea.commands.GitLineHandler;
import git4idea.fetch.GitFetchSupport;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class GitCodeReviewUtils {
    private GitCodeReviewUtils() {
    }

    public static CompletableFuture<?> fetch(GitRepository repository, GitRemote remote, String refspec) {
        return supplyAsync(() -> {
            GitFetchSupport.fetchSupport(repository.getProject()).fetch(repository, remote, refspec).throwExceptionIfFailed();
            return null;
        });
    }

    public static CompletableFuture<Boolean> testRevisionsExist(GitRepository repository, List<String> revisions) {
        return supplyAsync(() -> {
            GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.CAT_FILE);
            h.setSilent(true);
            h.addParameters("--batch-check=%(objecttype)");
            h.endOptions();
            h.setInputProcessor(GitHandlerInputProcessorUtil.writeLines(revisions, StandardCharsets.UTF_8));

            return !Git.getInstance().runCommand(h).getOutputOrThrow().contains("missing");
        });
    }

    public static CompletableFuture<Boolean> testIsAncestor(GitRepository repository, String potentialAncestorRev, String rev) {
        return supplyAsync(() -> {
            GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.MERGE_BASE);
            h.setSilent(true);
            h.addParameters("--is-ancestor", potentialAncestorRev, rev);
            return Git.getInstance().runCommand(h).success();
        });
    }

    public static CompletableFuture<List<TextFilePatch>> loadPatches(GitRepository repository, String baseRev, String headRev) {
        return loadPatches(repository, baseRev, headRev, List.of());
    }

    /**
     * Reads the patches between two revisions from the local repository.
     * <p>
     * The command detects a rename and uses three context lines, which matches the GitLab and the GitHub defaults.
     *
     * @param paths limits the output to these repository-relative paths. An empty collection reads every path.
     * @return the parsed patches, or an empty list when the command fails.
     */
    public static CompletableFuture<List<TextFilePatch>> loadPatches(GitRepository repository,
                                                                     String baseRev,
                                                                     String headRev,
                                                                     Collection<String> paths) {
        GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.DIFF);
        h.setSilent(true);
        h.addParameters("--no-ext-diff", "--no-color", "-M", "-U3", baseRev, headRev);
        h.endOptions();
        h.addParameters(new ArrayList<>(paths));

        return supplyAsync(() -> {
            String output = Git.getInstance().runCommand(h).getOutputOrThrow();
            if (output.isEmpty()) {
                return List.of();
            }

            return new PatchReader(output + "\n").readTextPatches();
        });
    }

    private static <T extends @Nullable Object> CompletableFuture<T> supplyAsync(ThrowingSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            }
            catch (VcsException | PatchSyntaxException e) {
                throw new CompletionException(e);
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T extends @Nullable Object> {
        T get() throws VcsException, PatchSyntaxException;
    }
}
