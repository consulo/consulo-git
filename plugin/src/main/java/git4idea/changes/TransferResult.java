// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.changes;

import org.jspecify.annotations.Nullable;

record TransferResult(int line, boolean exact) {
    static TransferResult exactTransfer(int line) {
        return new TransferResult(line, true);
    }

    static TransferResult estimatedTransfer(int line) {
        return new TransferResult(line, false);
    }

    @Nullable Integer exactLocation() {
        return exact ? line : null;
    }
}
