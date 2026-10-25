// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.repo;

import consulo.application.ReadAction;
import consulo.logging.Logger;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class GitSubmodule {
    private static final Logger LOG = Logger.getInstance(GitSubmodule.class);

    private final GitRepository myRepository;
    private final GitRepository myParent;

    public GitSubmodule(GitRepository repository, GitRepository parent) {
        myRepository = repository;
        myParent = parent;
    }

    public GitRepository getRepository() {
        return myRepository;
    }

    public GitRepository getParent() {
        return myParent;
    }

    public static @Nullable GitSubmodule asSubmodule(GitRepository repository) {
        GitRepositoryManager repositoryManager = GitRepositoryManager.getInstance(repository.getProject());
        for (GitRepository candidate : repositoryManager.getRepositories()) {
            if (isParentRepositoryFor(candidate, repository)) {
                return new GitSubmodule(repository, candidate);
            }
        }
        return null;
    }

    private static boolean isParentRepositoryFor(GitRepository parent, GitRepository submodule) {
        return VirtualFileUtil.isAncestor(parent.getRoot(), submodule.getRoot(), true) &&
            !parent.getSubmodules().isEmpty() &&
            ReadAction.compute(() -> parent.getRoot().isValid() && parent.getSubmodules().stream()
                .anyMatch(module -> submodule.getRoot().equals(parent.getRoot().findFileByRelativePath(module.getPath()))));
    }

    public static boolean isSubmodule(GitRepository repository) {
        return asSubmodule(repository) != null;
    }

    public static Collection<GitRepository> getDirectSubmodules(GitRepository repository) {
        List<GitRepository> result = new ArrayList<>();
        for (GitSubmoduleInfo module : repository.getSubmodules()) {
            VirtualFile submoduleDir = repository.getRoot().findFileByRelativePath(module.getPath());
            if (submoduleDir == null) {
                LOG.debug("submodule dir not found at declared path [" + module.getPath() + "] of root [" + repository.getRoot() + "]");
                continue;
            }
            GitRepository submodule = GitRepositoryManager.getInstance(repository.getProject()).getRepositoryForRoot(submoduleDir);
            if (submodule == null) {
                LOG.warn("Submodule not registered as a repository: " + submoduleDir);
                continue;
            }
            result.add(submodule);
        }
        return result;
    }
}
