// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.config;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.TopicAPI;

@TopicAPI(ComponentScope.PROJECT)
public interface GitVcsSettingsListener {
    default void showTagsChanged(boolean value) {
    }

    default void pathToGitChanged() {
    }

    default void incomingCommitsCheckStrategyChanged(GitIncomingRemoteCheckStrategy strategy) {
    }
}
