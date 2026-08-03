// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.ui.branch.popup;

import consulo.project.Project;
import consulo.ui.ex.awt.SeparatorWithText;
import consulo.versionControlSystem.distributed.ui.branch.popup.*;
import git4idea.GitBranch;
import git4idea.GitLocalBranch;
import git4idea.GitReference;
import git4idea.GitRemoteBranch;
import git4idea.branch.GitBranchesCollection;
import org.jspecify.annotations.Nullable;

import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Generic counterpart of {@code com.intellij.vcs.git.branch.tree.GitBranchesTreeSingleRepoModel}.
 * <p>
 * Supplies the LOCAL/REMOTE branch groups of a single {@link GitDvcsRepositoryModel} to the abstract
 * tree model. Tags and recent branches are omitted since this consulo-git model does not load them.
 *
 * @author VISTALL
 */
public class GitBranchesTreeSingleRepoModel extends DvcsBranchesTreeModel {
    private static final List<DvcsRefType> SUPPORTED_TYPES = List.of(GitRefType.LOCAL, GitRefType.REMOTE);

    private final GitDvcsRepositoryModel myRepository;
    private final SeparatorWithText myActionsSeparator = new SeparatorWithText();

    public GitBranchesTreeSingleRepoModel(Project project, GitDvcsRepositoryModel repository, List<Object> topLevelActions) {
        super(project, topLevelActions, List.<DvcsRepositoryModel>of(repository));
        myRepository = repository;
    }

    @Override
    protected List<DvcsRefType> getSupportedRefTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    protected Collection<DvcsRef> getRefs(DvcsRefType type) {
        GitBranchesCollection branches = myRepository.repository().getBranches();
        List<DvcsRef> result = new ArrayList<>();
        if (type == GitRefType.LOCAL) {
            for (GitLocalBranch branch : branches.getLocalBranches()) {
                result.add(new GitRefAdapter(branch));
            }
        }
        else if (type == GitRefType.REMOTE) {
            for (GitRemoteBranch branch : branches.getRemoteBranches()) {
                result.add(new GitRefAdapter(branch));
            }
        }
        return result;
    }

    @Override
    public DvcsRefType getRefType(DvcsRef ref) {
        GitReference reference = ref instanceof GitRefAdapter adapter ? adapter.reference() : null;
        return reference instanceof GitBranch branch && branch.isRemote() ? GitRefType.REMOTE : GitRefType.LOCAL;
    }

    @Override
    protected List<Object> getChildren(@Nullable Object parent) {
        if (parent == null || notHaveFilteredNodes()) {
            return List.of();
        }
        if (parent == TreeRoot.INSTANCE) {
            return getTopLevelNodes();
        }
        if (parent instanceof DvcsRefType type) {
            return myBranchesTreeCache.computeIfAbsent(
                parent,
                k -> DvcsBranchesTreeModelUtil.buildBranchTreeNodes(type, getCorrespondingTree(type), List.of(), null));
        }
        if (parent instanceof BranchesPrefixGroup group) {
            return myBranchesTreeCache.computeIfAbsent(parent, k -> {
                List<Object> nodes = DvcsBranchesTreeModelUtil.buildBranchTreeNodes(
                    group.type(), getCorrespondingTree(group.type()), group.prefix(), null);
                nodes.sort(getSubTreeComparator());
                return nodes;
            });
        }
        return List.of();
    }

    private List<Object> getTopLevelNodes() {
        List<Object> matchedActions = new ArrayList<>(myActionsTree.getMatch());

        List<Object> typeNodes = new ArrayList<>();
        for (DvcsRefType type : getSupportedRefTypes()) {
            if (!getCorrespondingTree(type).isEmpty()) {
                typeNodes.add(type);
            }
        }

        if (!typeNodes.isEmpty()) {
            matchedActions = DvcsBranchesTreeModelUtil.addSeparatorIfNeeded(matchedActions, myActionsSeparator);
        }

        List<Object> result = new ArrayList<>(matchedActions);
        result.addAll(typeNodes);
        return result;
    }

    private boolean notHaveFilteredNodes() {
        return getNameMatcher() != null && myActionsTree.isEmpty() && areRefTreesEmpty();
    }

    @Override
    public TreePath getPreferredSelection() {
        Object preferred = myActionsTree.getTopMatch();
        if (preferred == null) {
            preferred = getPreferredRef();
        }
        return preferred != null ? DvcsBranchesTreeModelUtil.createTreePathFor(this, preferred) : null;
    }

    @Nullable
    private DvcsRef getPreferredRef() {
        if (getNameMatcher() == null) {
            GitLocalBranch current = myRepository.repository().getCurrentBranch();
            return current != null ? new GitRefAdapter(current) : null;
        }
        DvcsRef localTop = topMatch(GitRefType.LOCAL);
        return localTop != null ? localTop : topMatch(GitRefType.REMOTE);
    }

    @Nullable
    private DvcsRef topMatch(DvcsRefType type) {
        DvcsBranchesTreeModelUtil.LazyRefsSubtreeHolder holder = myRefTrees.get(type);
        return holder == null ? null : holder.getTopMatch();
    }
}
