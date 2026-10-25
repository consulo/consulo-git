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
package git4idea.ui.branch;

/**
 * {@link #equals} and {@link #hashCode} deliberately ignore the open callback: a lambda compares by reference, so including it would
 * make two reviews with the same title compare unequal whenever they are built by separate lookups.
 */
public final class GitBranchReview {
    private final String myTitle;
    private final Runnable myOnOpen;

    public GitBranchReview(String title, Runnable onOpen) {
        myTitle = title;
        myOnOpen = onOpen;
    }

    public String getTitle() {
        return myTitle;
    }

    /**
     * Opens the review in its hosting plugin's own UI.
     */
    public void open() {
        myOnOpen.run();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GitBranchReview review && myTitle.equals(review.myTitle);
    }

    @Override
    public int hashCode() {
        return myTitle.hashCode();
    }
}
