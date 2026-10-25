// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.branch;

import git4idea.repo.GitRepository;

import java.util.Collection;
import java.util.List;

public record GitNewBranchOptions(String name,
                                  boolean checkout,
                                  boolean reset,
                                  boolean setTracking,
                                  Collection<GitRepository> repositories,
                                  boolean unsetUpstream) {
    public GitNewBranchOptions(String name) {
        this(name, true);
    }

    public GitNewBranchOptions(String name, boolean checkout) {
        this(name, checkout, false);
    }

    public GitNewBranchOptions(String name, boolean checkout, boolean reset) {
        this(name, checkout, reset, false);
    }

    public GitNewBranchOptions(String name, boolean checkout, boolean reset, boolean setTracking) {
        this(name, checkout, reset, setTracking, List.of());
    }

    public GitNewBranchOptions(String name, boolean checkout, boolean reset, boolean setTracking, Collection<GitRepository> repositories) {
        this(name, checkout, reset, setTracking, repositories, false);
    }
}
