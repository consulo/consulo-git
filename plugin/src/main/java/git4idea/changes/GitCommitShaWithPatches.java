// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.changes;

import consulo.versionControlSystem.change.patch.FilePatch;

import java.util.List;

public class GitCommitShaWithPatches {
    private final String mySha;
    private final List<String> myParents;
    private final List<FilePatch> myPatches;

    public GitCommitShaWithPatches(String sha, List<String> parents, List<FilePatch> patches) {
        mySha = sha;
        myParents = parents;
        myPatches = patches;
    }

    public String getSha() {
        return mySha;
    }

    public List<String> getParents() {
        return myParents;
    }

    public List<FilePatch> getPatches() {
        return myPatches;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GitCommitShaWithPatches that)) {
            return false;
        }
        return mySha.equals(that.mySha);
    }

    @Override
    public int hashCode() {
        return mySha.hashCode();
    }

    @Override
    public String toString() {
        return "GitCommitShaWithPatches(sha='" + mySha + "', parents=" + myParents + ", patches=" + myPatches + ")";
    }
}
