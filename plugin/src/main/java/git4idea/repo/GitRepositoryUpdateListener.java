// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.repo;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.TopicAPI;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;

@TopicAPI(ComponentScope.APPLICATION)
public interface GitRepositoryUpdateListener {
    void repositoryUpdated(Project updatedInProject, VirtualFile root);
}
