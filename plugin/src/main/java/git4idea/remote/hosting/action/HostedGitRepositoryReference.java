// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote.hosting.action;

import consulo.localize.LocalizeValue;
import org.jspecify.annotations.Nullable;

import java.net.URI;

public interface HostedGitRepositoryReference {
    LocalizeValue getName();

    @Nullable URI buildWebURI();
}
