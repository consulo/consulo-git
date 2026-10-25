// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.changes;

import consulo.versionControlSystem.change.patch.TextFilePatch;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class MutableLinearGitFileHistory implements GitFileHistory {
    private final List<String> myCommitHashes;
    private final Map<String, MutableLinearGitFileHistoryEntry> myHistory = new LinkedHashMap<>();

    MutableLinearGitFileHistory(List<String> commitHashes) {
        myCommitHashes = commitHashes;
        for (String sha : commitHashes) {
            myHistory.put(sha, new MutableLinearGitFileHistoryEntry(null));
        }
    }

    private @Nullable String getFirstKnownFilePath() {
        for (MutableLinearGitFileHistoryEntry entry : myHistory.values()) {
            if (entry.patch() != null) {
                return entry.patch().getBeforeName();
            }
        }
        throw new IllegalStateException("Empty history");
    }

    @Nullable String getLastKnownFilePath() {
        TextFilePatch lastPatch = null;
        for (MutableLinearGitFileHistoryEntry entry : myHistory.values()) {
            if (entry.patch() != null) {
                lastPatch = entry.patch();
            }
        }
        if (lastPatch == null) {
            throw new IllegalStateException("Empty history");
        }
        String lastFilePath = lastPatch.getAfterName();
        return lastFilePath != null ? lastFilePath : getFirstKnownFilePath();
    }

    @Override
    public @Nullable String findStartCommit() {
        return myCommitHashes.isEmpty() ? null : myCommitHashes.get(0);
    }

    @Override
    public @Nullable String findFirstParent(String commitSha) {
        String parentSha = null;
        for (String commitHash : myCommitHashes) {
            if (commitSha.equals(commitHash)) {
                break;
            }
            parentSha = commitHash;
        }
        return parentSha;
    }

    void append(String commitSha, TextFilePatch patch) {
        MutableLinearGitFileHistoryEntry entry = myHistory.get(commitSha);
        if (entry == null) {
            throw new IllegalStateException("Adding entry for an unknown commit " + commitSha + ". Known commits - " + myCommitHashes);
        }
        if (entry.patch() != null) {
            throw new IllegalStateException("Entry patch was already recorded for commit " + commitSha + ". " +
                "Existing patch - " + getHeaderString(entry.patch()) + ". " +
                "New patch - " + getHeaderString(patch));
        }
        myHistory.put(commitSha, new MutableLinearGitFileHistoryEntry(patch));
    }

    void append(String commitSha, @Nullable String filePath) {
        MutableLinearGitFileHistoryEntry entry = myHistory.get(commitSha);
        if (entry == null) {
            throw new IllegalStateException("Adding entry for an unknown commit " + commitSha + ". Known commits - " + myCommitHashes);
        }
        myHistory.put(commitSha, new MutableLinearGitFileHistoryEntry(null, filePath));
    }

    @Override
    public boolean contains(String commitSha, String filePath) {
        String lastPath = null;
        for (Map.Entry<String, MutableLinearGitFileHistoryEntry> mapEntry : myHistory.entrySet()) {
            String entryPath = mapEntry.getValue().filePath();
            if (entryPath != null) {
                lastPath = entryPath;
            }

            if (mapEntry.getKey().equals(commitSha) && filePath.equals(lastPath)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int compare(String commitSha1, String commitSha2) {
        if (commitSha1.equals(commitSha2)) {
            return 0;
        }

        for (String sha : myHistory.keySet()) {
            if (sha.equals(commitSha1)) {
                return -1;
            }
            if (sha.equals(commitSha2)) {
                return 1;
            }
        }
        throw new IllegalStateException("Unknown commit sha");
    }

    @Override
    public List<TextFilePatch> getPatchesBetween(String parent, String child) {
        List<TextFilePatch> patches = new ArrayList<>();

        boolean foundParent = false;
        for (Map.Entry<String, MutableLinearGitFileHistoryEntry> mapEntry : myHistory.entrySet()) {
            String sha = mapEntry.getKey();
            if (!foundParent) {
                if (sha.equals(parent)) {
                    foundParent = true;
                }
            }
            else {
                TextFilePatch patch = mapEntry.getValue().patch();
                if (patch != null) {
                    patches.add(patch);
                }
                if (sha.equals(child)) {
                    break;
                }
            }
        }
        return patches;
    }

    static String getFilePath(TextFilePatch patch) {
        String afterName = patch.getAfterName();
        return afterName != null ? afterName : patch.getBeforeName();
    }

    private static String getHeaderString(TextFilePatch patch) {
        return patch.getBeforeVersionId() + " " + patch.getBeforeName() + " -> " + patch.getAfterVersionId() + " " + patch.getAfterName();
    }
}
