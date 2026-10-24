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
package git4idea.remote;

import consulo.util.io.OSAgnosticPathUtil;
import consulo.util.io.URLUtil;
import consulo.util.io.UriUtil;
import consulo.util.lang.StringUtil;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Parses and matches Git remote URLs.
 * The functions need no hosting service.
 */
public final class GitRemoteUrlUtil {
    private GitRemoteUrlUtil() {
    }

    public static boolean isSshUrl(String url) {
        int schemeSeparator = url.indexOf(URLUtil.SCHEME_SEPARATOR);
        if (schemeSeparator >= 0) {
            return switch (url.substring(0, schemeSeparator)) {
                case "ssh", "git+ssh", "ssh+git" -> true;
                default -> false;
            };
        }
        int colon = url.indexOf(':');
        int slash = url.indexOf('/');
        return colon >= 0 && !(slash >= 0 && slash < colon) && !OSAgnosticPathUtil.startsWithWindowsDrive(url);
    }

    public static String removeProtocolPrefix(String url) {
        int index = url.indexOf('@');
        if (index != -1) {
            return url.substring(index + 1);
        }
        index = url.indexOf("://");
        if (index != -1) {
            return url.substring(index + 3);
        }
        return url;
    }

    public static @Nullable URI getUriFromRemoteUrl(String remoteUrl) {
        String fixed = StringUtil.trimEnd(StringUtil.trimEnd(UriUtil.trimTrailingSlashes(remoteUrl), "/"), ".git");
        try {
            if (!fixed.contains(URLUtil.SCHEME_SEPARATOR)) {
                // scp-style
                return new URI(URLUtil.HTTPS_PROTOCOL + URLUtil.SCHEME_SEPARATOR + removeProtocolPrefix(fixed).replace(":/", "/").replace(':', '/'));
            }
            return new URI(fixed);
        }
        catch (URISyntaxException e) {
            return null;
        }
    }

    public static boolean match(URI serverUri, String gitRemoteUrl) {
        URI remoteUri = getUriFromRemoteUrl(gitRemoteUrl);
        if (remoteUri == null) {
            return false;
        }
        if (!StringUtil.equalsIgnoreCase(serverUri.getHost(), remoteUri.getHost())) {
            return false;
        }
        String serverPath = serverUri.getPath();
        if (serverPath != null && !serverPath.equals("/")) {
            String remoteUriPath = remoteUri.getPath();
            if (remoteUriPath == null) {
                return false;
            }
            if (!StringUtil.startsWithIgnoreCase(remoteUriPath, serverPath)) {
                return false;
            }
        }
        return true;
    }

    public static boolean matchHost(URI serverUri, String gitRemoteUrl) {
        URI remoteUri = getUriFromRemoteUrl(gitRemoteUrl);
        if (remoteUri == null) {
            return false;
        }
        return StringUtil.equalsIgnoreCase(serverUri.getHost(), remoteUri.getHost());
    }
}
