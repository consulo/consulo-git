/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package git4idea.roots;

import consulo.annotation.component.ExtensionImpl;
import consulo.versionControlSystem.VcsKey;
import consulo.versionControlSystem.VcsRootChecker;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.GitVcs;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * @author Kirill Likhodedov
 */
@ExtensionImpl
public class GitRootChecker extends VcsRootChecker {
    @Override
    public boolean isRoot(@Nonnull VirtualFile file) {
        Path path = GitUtil.toNioPathOrNull(file);
        return path != null && GitUtil.isGitRoot(path);
    }

    @Override
    public boolean validateRoot(@Nonnull VirtualFile file) {
        return isRoot(file);
    }

    @Override
    public boolean isRoot(@Nonnull String path) {
        Path folder;
        try {
            folder = Path.of(path);
        }
        catch (InvalidPathException e) {
            return false;
        }
        return GitUtil.isGitRoot(folder);
    }

    @Override
    @Nonnull
    public VcsKey getSupportedVcs() {
        return GitVcs.getKey();
    }

    @Override
    public boolean isVcsDir(@Nullable String path) {
        return path != null && path.toLowerCase().endsWith(GitUtil.DOT_GIT);
    }
}
