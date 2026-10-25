// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.ui.branch;

import git4idea.GitBranch;
import git4idea.GitRemoteBranch;
import org.jspecify.annotations.Nullable;

import java.util.List;

public interface MergeDirectionModel<RepoMapping> {
    RepoMapping getBaseRepo();

    @Nullable GitRemoteBranch getBaseBranch();

    void setBaseBranch(@Nullable GitRemoteBranch baseBranch);

    @Nullable RepoMapping getHeadRepo();

    @Nullable GitBranch getHeadBranch();

    boolean getHeadSetByUser();

    void setHeadSetByUser(boolean headSetByUser);

    void setHead(@Nullable RepoMapping repo, @Nullable GitBranch branch);

    void addAndInvokeDirectionChangesListener(Runnable listener);

    List<RepoMapping> getKnownRepoMappings();
}
