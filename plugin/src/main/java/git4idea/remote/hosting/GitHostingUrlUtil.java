// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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

import consulo.http.client.ServerPath;
import consulo.logging.Logger;
import consulo.util.io.URLUtil;
import git4idea.remote.GitRemoteUrlCoordinates;
import git4idea.remote.GitRemoteUrlUtil;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class GitHostingUrlUtil {
    private GitHostingUrlUtil() {
    }

    public static boolean isSshUrl(String url) {
        return GitRemoteUrlUtil.isSshUrl(url);
    }

    public static String removeProtocolPrefix(String url) {
        return GitRemoteUrlUtil.removeProtocolPrefix(url);
    }

    public static @Nullable URI getUriFromRemoteUrl(String remoteUrl) {
        return GitRemoteUrlUtil.getUriFromRemoteUrl(remoteUrl);
    }

    public static boolean match(URI serverUri, String gitRemoteUrl) {
        return GitRemoteUrlUtil.match(serverUri, gitRemoteUrl);
    }

    public static boolean matchHost(URI serverUri, String gitRemoteUrl) {
        return GitRemoteUrlUtil.matchHost(serverUri, gitRemoteUrl);
    }

    public static <S extends ServerPath> CompletableFuture<@Nullable S> findServerAt(
        Logger log,
        GitRemoteUrlCoordinates coordinates,
        Function<URI, CompletableFuture<@Nullable S>> serverCheck
    ) {
        URI uri = getUriFromRemoteUrl(coordinates.url());
        log.debug("Extracted URI " + uri + " from remote " + coordinates.url());
        if (uri == null) {
            return CompletableFuture.completedFuture(null);
        }

        String host = uri.getHost();
        if (host == null) {
            return CompletableFuture.completedFuture(null);
        }
        String path = uri.getPath();
        if (path == null) {
            return CompletableFuture.completedFuture(null);
        }
        List<String> pathParts = Arrays.asList((path.startsWith("/") ? path.substring(1) : path).split("/", -1));
        if (pathParts.size() < 2) {
            return CompletableFuture.completedFuture(null);
        }
        String serverSuffix = pathParts.size() == 2 ? null : String.join("/", pathParts.subList(0, pathParts.size() - 2));

        List<URI> serverUris = new ArrayList<>();
        try {
            serverUris.add(new URI(URLUtil.HTTPS_PROTOCOL, host, serverSuffix, null));
            serverUris.add(new URI(URLUtil.HTTP_PROTOCOL, host, serverSuffix, null));
            serverUris.add(new URI(URLUtil.HTTP_PROTOCOL, null, host, 8080, serverSuffix, null, null));
        }
        catch (URISyntaxException e) {
            return CompletableFuture.completedFuture(null);
        }
        return findServerAt(log, serverUris, 0, serverCheck);
    }

    private static <S extends ServerPath> CompletableFuture<@Nullable S> findServerAt(
        Logger log,
        List<URI> serverUris,
        int index,
        Function<URI, CompletableFuture<@Nullable S>> serverCheck
    ) {
        if (index >= serverUris.size()) {
            return CompletableFuture.completedFuture(null);
        }
        URI serverUri = serverUris.get(index);
        log.debug("Looking for server at " + serverUri);
        CompletableFuture<@Nullable S> check;
        try {
            check = serverCheck.apply(serverUri);
        }
        catch (Throwable ignored) {
            return findServerAt(log, serverUris, index + 1, serverCheck);
        }
        return check.handle((server, error) -> error == null ? server : null).thenCompose(server -> {
            if (server != null) {
                log.debug("Found server at " + serverUri);
                return CompletableFuture.completedFuture(server);
            }
            return findServerAt(log, serverUris, index + 1, serverCheck);
        });
    }
}
