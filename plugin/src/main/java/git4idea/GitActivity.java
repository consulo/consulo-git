// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea;

import consulo.localHistory.ActivityId;

public final class GitActivity {
    public static final ActivityId Checkout = createId("Checkout");
    public static final ActivityId Merge = createId("Merge");
    public static final ActivityId Rebase = createId("Rebase");
    public static final ActivityId Abort = createId("Abort");
    public static final ActivityId Reset = createId("Reset");
    public static final ActivityId Stash = createId("Stash");
    public static final ActivityId Unstash = createId("Unstash");
    public static final ActivityId CherryPick = createId("CherryPick");
    public static final ActivityId Revert = createId("Revert");

    private static ActivityId createId(String kind) {
        return new ActivityId(GitActivityPresentationProvider.ID, kind);
    }

    private GitActivity() {
    }
}
