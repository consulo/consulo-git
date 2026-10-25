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
package git4idea.remote.hosting;

import consulo.util.io.URLUtil;
import git4idea.remote.GitRemoteUrlCoordinates;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;

public record HostedGitRepositoryRemote(String name, URI serverUri, String path, @Nullable String httpUrl, @Nullable String sshUrl) {
    /**
     * Creates the descriptor of the remote for the hosted repository at [path].
     * The descriptor uses the host of [defaultCoordinates], so it also matches a remote with an SSH alias.
     * [httpUrl] and [sshUrl] are the repository URLs from the hosting API.
     */
    public static HostedGitRepositoryRemote createHostedGitRepositoryRemote(String name,
                                                                            String path,
                                                                            @Nullable String httpUrl,
                                                                            @Nullable String sshUrl,
                                                                            GitRemoteUrlCoordinates defaultCoordinates) {
        return new HostedGitRepositoryRemote(
            name,
            getServerUri(path, httpUrl, defaultCoordinates),
            path,
            httpUrl,
            sshUrl == null ? null : getSshUrl(sshUrl, defaultCoordinates)
        );
    }

    /**
     * Gets the server URI with the host of [defaultCoordinates].
     * HTTP remotes keep the web path from [httpUrl]. SSH remotes use the root path.
     */
    private static URI getServerUri(String path, @Nullable String httpUrl, GitRemoteUrlCoordinates defaultCoordinates) {
        URI remoteUri = GitHostingUrlUtil.getUriFromRemoteUrl(defaultCoordinates.url());
        if (remoteUri == null) {
            throw new IllegalArgumentException("Invalid remote URL: " + defaultCoordinates.url());
        }
        if (GitHostingUrlUtil.isSshUrl(defaultCoordinates.url())) {
            return remoteUri.resolve("/");
        }
        String webPath = "/";
        URI httpUri = httpUrl == null ? null : GitHostingUrlUtil.getUriFromRemoteUrl(httpUrl);
        String httpPath = httpUri == null ? null : httpUri.getPath();
        if (httpPath != null && httpPath.endsWith("/" + path)) {
            webPath = httpPath.substring(0, httpPath.length() - path.length());
        }
        return remoteUri.resolve(webPath);
    }

    /**
     * Gets [sshUrl] with the host of [defaultCoordinates].
     * Keeps an explicit port from [defaultCoordinates].
     * Without such a port, the URL keeps the port of [sshUrl] only when the host does not change.
     * With another host, such as an SSH alias, the SSH config gives the port, so the URL has no port.
     */
    private static String getSshUrl(String sshUrl, GitRemoteUrlCoordinates defaultCoordinates) {
        if (!GitHostingUrlUtil.isSshUrl(defaultCoordinates.url()) || !GitHostingUrlUtil.isSshUrl(sshUrl)) {
            return sshUrl;
        }
        URI remoteUri = GitHostingUrlUtil.getUriFromRemoteUrl(defaultCoordinates.url());
        if (remoteUri == null) {
            return sshUrl;
        }
        URI sshUri = GitHostingUrlUtil.getUriFromRemoteUrl(sshUrl);
        if (sshUri == null) {
            return sshUrl;
        }
        if (remoteUri.getHost().equalsIgnoreCase(sshUri.getHost()) && (remoteUri.getPort() == -1 || remoteUri.getPort() == sshUri.getPort())) {
            return sshUrl;
        }

        if (sshUrl.contains(URLUtil.SCHEME_SEPARATOR)) {
            URI uri;
            try {
                uri = new URI(sshUrl);
            }
            catch (URISyntaxException e) {
                return sshUrl;
            }
            if (remoteUri.getPort() == -1) {
                String userInfo = uri.getUserInfo() == null ? "" : uri.getUserInfo() + "@";
                String uriPath = uri.getPath() == null ? "" : uri.getPath();
                return userInfo + remoteUri.getHost() + ":" + (uriPath.startsWith("/") ? uriPath.substring(1) : uriPath);
            }
            try {
                return new URI(uri.getScheme(), uri.getUserInfo(), remoteUri.getHost(), remoteUri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
            }
            catch (URISyntaxException e) {
                return sshUrl;
            }
        }

        int hostStart = sshUrl.indexOf('@') + 1;
        int hostEnd = sshUrl.indexOf(':', hostStart);
        if (hostEnd < 0) {
            return sshUrl;
        }
        if (remoteUri.getPort() != -1) {
            String userPart = sshUrl.substring(0, hostStart);
            String userInfo = userPart.endsWith("@") ? userPart.substring(0, userPart.length() - 1) : userPart;
            String tail = sshUrl.substring(hostEnd + 1);
            String sshPath = "/" + (tail.startsWith("/") ? tail.substring(1) : tail);
            try {
                return new URI("ssh", userInfo.isEmpty() ? null : userInfo, remoteUri.getHost(), remoteUri.getPort(), sshPath, null, null).toString();
            }
            catch (URISyntaxException e) {
                return sshUrl;
            }
        }
        return sshUrl.substring(0, hostStart) + remoteUri.getHost() + sshUrl.substring(hostEnd);
    }
}
