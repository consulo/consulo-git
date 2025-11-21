/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

package git4idea;

import consulo.project.Project;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.branch.GitBranchUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class GitBranchesSearcher {
    private final static Logger LOG = LoggerFactory.getLogger(GitBranchesSearcher.class);
    private final GitBranch myLocal;
    private GitBranch myRemote;

    public GitBranchesSearcher(Project project, VirtualFile root, boolean findRemote) throws VcsException {
        LOG.debug("constructing, root: {} findRemote = {}", root.getPath(), findRemote);
        Set<GitBranch> usedBranches = new HashSet<>();
        myLocal = GitBranchUtil.getCurrentBranch(project, root);
        LOG.debug("local: {}", myLocal);
        if (myLocal == null) {
            return;
        }
        usedBranches.add(myLocal);

        GitBranch remote = myLocal;
        while (true) {
            remote = GitBranchUtil.tracked(project, root, remote.getName());
            if (remote == null) {
                LOG.debug("remote == null, exiting");
                return;
            }

            if (!findRemote || remote.isRemote()) {
                LOG.debug("remote found, isRemote: {} remoteName: {}", remote.isRemote(), remote.getFullName());
                myRemote = remote;
                return;
            }

            if (usedBranches.contains(remote)) {
                LOG.debug("loop found for: {}, exiting", remote.getFullName());
                return;
            }
            usedBranches.add(remote);
        }
    }

    public GitBranch getLocal() {
        return myLocal;
    }

    public GitBranch getRemote() {
        return myRemote;
    }
}
