// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees;

import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;

import java.nio.file.Path;

/**
 * The source project and the target worktree directory of an in-progress worktree creation.
 */
public record GitWorktreeConfigCopyContext(Project project, VirtualFile sourceRoot, Path targetWorktreeDir) {
}
