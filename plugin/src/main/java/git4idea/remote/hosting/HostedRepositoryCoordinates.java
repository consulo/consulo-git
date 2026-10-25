// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote.hosting;

import consulo.http.client.ServerPath;

import java.net.URI;

public interface HostedRepositoryCoordinates {
    ServerPath getServerPath();

    URI getWebURI();
}
