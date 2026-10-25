// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ui.branch;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.TopicAPI;

/**
 * Notifies when new value for {@link GitCurrentBranchPresenter#getPresentation(git4idea.repo.GitRepository)} is available and should be polled.
 */
@TopicAPI(ComponentScope.PROJECT)
@FunctionalInterface
public interface GitCurrentBranchPresentationUpdatedListener {
    void presentationUpdated();
}
