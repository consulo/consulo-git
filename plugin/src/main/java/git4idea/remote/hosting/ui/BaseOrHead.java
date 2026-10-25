// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote.hosting.ui;

import consulo.localize.LocalizeValue;
import consulo.review.localize.ReviewLocalize;

public enum BaseOrHead {
    Base(ReviewLocalize.reviewDetailsResolveConflictsBase()),
    Head(ReviewLocalize.reviewDetailsResolveConflictsHead());

    private final LocalizeValue myText;

    BaseOrHead(LocalizeValue text) {
        myText = text;
    }

    public LocalizeValue getText() {
        return myText;
    }
}
