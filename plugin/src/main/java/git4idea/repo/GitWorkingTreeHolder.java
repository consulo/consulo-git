// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.repo;

import git4idea.GitWorkingTree;

import java.util.Collection;

public interface GitWorkingTreeHolder {
    Collection<GitWorkingTree> getWorkingTrees();

    void scheduleReload();
}
