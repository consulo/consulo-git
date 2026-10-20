// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ignore;

import consulo.annotation.component.ExtensionImpl;
import consulo.project.Project;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsKey;
import consulo.versionControlSystem.VcsSharedChecker;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitVcs;
import git4idea.index.GitIndexUtil;

import java.nio.file.Path;
import java.util.List;

@ExtensionImpl
public class GitIgnoreInStoreDirSharedChecker implements VcsSharedChecker {
    @Override
    public VcsKey getSupportedVcs() {
        return GitVcs.getKey();
    }

    @Override
    public boolean isPathSharedInVcs(Project project, Path path) throws VcsException {
        Path projectConfigDirPath = GitIgnoreInStoreDirGenerator.getDirectoryStorePath(project);
        if (projectConfigDirPath == null) {
            return false;
        }
        FilePath projectConfigDir = VcsUtil.getFilePath(projectConfigDirPath.toFile(), true);
        VirtualFile vcsRoot = VcsUtil.getVcsRootFor(project, projectConfigDir);
        if (vcsRoot == null) {
            return false;
        }
        return !GitIndexUtil.listStaged(project, vcsRoot, List.of(projectConfigDir)).isEmpty();
    }
}
