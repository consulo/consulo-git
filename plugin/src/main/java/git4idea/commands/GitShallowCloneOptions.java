// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.commands;

import org.jspecify.annotations.Nullable;

public final class GitShallowCloneOptions {
    private final @Nullable Integer myDepth;

    public GitShallowCloneOptions(@Nullable Integer depth) {
        myDepth = depth;
    }

    public @Nullable Integer getDepth() {
        return myDepth;
    }
}
