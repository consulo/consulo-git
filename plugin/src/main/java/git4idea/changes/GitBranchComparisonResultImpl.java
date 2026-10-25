// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.changes;

import consulo.logging.Logger;
import consulo.project.Project;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.change.RefComparisonChange;
import consulo.versionControlSystem.change.patch.FilePatch;
import consulo.versionControlSystem.change.patch.TextFilePatch;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitRevisionNumber;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class GitBranchComparisonResultImpl implements GitBranchComparisonResult {
    private static final Logger LOG = Logger.getInstance(GitBranchComparisonResult.class);

    private final Project myProject;
    private final VirtualFile myVcsRoot;
    private final String myBaseSha;
    private final String myMergeBaseSha;
    private final List<GitCommitShaWithPatches> myCommits;
    private final List<FilePatch> myHeadPatches;
    private final String myHeadSha;

    private final List<RefComparisonChange> myChanges = new ArrayList<>();
    private final Map<String, List<RefComparisonChange>> myChangesByCommits = new LinkedHashMap<>();
    private final Map<RefComparisonChange, GitTextFilePatchWithHistory> myDiffDataByChange = new LinkedHashMap<>();

    GitBranchComparisonResultImpl(Project project,
                                  VirtualFile vcsRoot,
                                  String baseSha,
                                  String mergeBaseSha,
                                  List<GitCommitShaWithPatches> commits,
                                  List<FilePatch> headPatches) {
        myProject = project;
        myVcsRoot = vcsRoot;
        myBaseSha = baseSha;
        myMergeBaseSha = mergeBaseSha;
        myCommits = commits;
        myHeadPatches = headPatches;
        myHeadSha = commits.get(commits.size() - 1).getSha();

        try {
            initForLinearHistory(commits);
        }
        catch (Exception e) {
            throw new RuntimeException("Unable to build branch comparison result between " + myBaseSha + " and " + myHeadSha +
                " via " + myMergeBaseSha + " - " + e.getMessage(), e);
        }
    }

    @Override
    public String getBaseSha() {
        return myBaseSha;
    }

    @Override
    public String getMergeBaseSha() {
        return myMergeBaseSha;
    }

    @Override
    public String getHeadSha() {
        return myHeadSha;
    }

    @Override
    public List<RefComparisonChange> getChanges() {
        return Collections.unmodifiableList(myChanges);
    }

    @Override
    public List<GitCommitShaWithPatches> getCommits() {
        return myCommits;
    }

    @Override
    public Map<String, List<RefComparisonChange>> getChangesByCommits() {
        return Collections.unmodifiableMap(myChangesByCommits);
    }

    @Override
    public Map<RefComparisonChange, GitTextFilePatchWithHistory> getPatchesByChange() {
        return Collections.unmodifiableMap(myDiffDataByChange);
    }

    private void initForLinearHistory(List<GitCommitShaWithPatches> commits) {
        Map<String, MutableLinearGitFileHistory> fileHistoriesByLastKnownFilePath = new HashMap<>();

        String previousCommitSha = myMergeBaseSha;

        List<String> commitsHashes = new ArrayList<>();
        commitsHashes.add(myMergeBaseSha);
        for (GitCommitShaWithPatches commit : commits) {
            commitsHashes.add(commit.getSha());
        }

        for (GitCommitShaWithPatches commitWithPatches : commits) {
            String commitSha = commitWithPatches.getSha();
            List<RefComparisonChange> commitChanges = new ArrayList<>();

            long knownParents = commitWithPatches.getParents().stream().filter(commitsHashes::contains).count();
            if (knownParents <= 1) {
                for (FilePatch patch : commitWithPatches.getPatches()) {
                    RefComparisonChange change = createChangeFromPatch(previousCommitSha, commitSha, patch);
                    commitChanges.add(change);

                    if (patch instanceof TextFilePatch textPatch) {
                        String beforePath = textPatch.getBeforeName();
                        String afterPath = textPatch.getAfterName();

                        MutableLinearGitFileHistory historyBefore = beforePath == null ? null : fileHistoriesByLastKnownFilePath.remove(beforePath);
                        MutableLinearGitFileHistory fileHistory = historyBefore != null
                            ? historyBefore
                            : startNewHistory(commitsHashes, previousCommitSha, beforePath);
                        fileHistory.append(commitSha, textPatch);

                        String path = Objects.requireNonNull(afterPath != null ? afterPath : beforePath);
                        fileHistoriesByLastKnownFilePath.put(path, fileHistory);

                        textPatch.setBeforeVersionId(previousCommitSha);
                        textPatch.setAfterVersionId(commitSha);
                        myDiffDataByChange.put(change, new GitTextFilePatchWithHistory(textPatch, false, fileHistory));
                    }
                    else {
                        LOG.debug("Non-text file " + GitBranchComparisonResult.getFilePath(patch) + " patch encountered in a commit " + commitSha + " diff");
                    }
                }
            }

            myChangesByCommits.put(commitWithPatches.getSha(), commitChanges);
            previousCommitSha = commitSha;
        }

        Map<String, MutableLinearGitFileHistory> fileHistoriesBySummaryFilePath = new HashMap<>();
        for (MutableLinearGitFileHistory history : fileHistoriesByLastKnownFilePath.values()) {
            fileHistoriesBySummaryFilePath.put(history.getLastKnownFilePath(), history);
        }

        for (FilePatch patch : myHeadPatches) {
            RefComparisonChange change = createChangeFromPatch(myMergeBaseSha, myHeadSha, patch);
            myChanges.add(change);

            if (patch instanceof TextFilePatch textPatch) {
                String beforePath = textPatch.getBeforeName();
                String afterPath = textPatch.getAfterName();

                String filePath = Objects.requireNonNull(afterPath != null ? afterPath : beforePath);
                MutableLinearGitFileHistory fileHistory = fileHistoriesBySummaryFilePath.get(filePath);
                if (fileHistory == null) {
                    LOG.warn("Unable to connect the cumulative patch for " + filePath + " to the commit history");
                    fileHistory = startNewHistory(commitsHashes, myMergeBaseSha, beforePath);
                    fileHistory.append(myHeadSha, afterPath);
                }
                textPatch.setBeforeVersionId(myBaseSha);
                textPatch.setAfterVersionId(myHeadSha);

                myDiffDataByChange.put(change, new GitTextFilePatchWithHistory(textPatch, true, fileHistory));
            }
            else {
                LOG.debug("Non-text file " + GitBranchComparisonResult.getFilePath(patch) + " patch encountered in a cumulative diff");
            }
        }
    }

    private static MutableLinearGitFileHistory startNewHistory(List<String> commitsHashes, String startCommitSha, @Nullable String startFilePath) {
        MutableLinearGitFileHistory history = new MutableLinearGitFileHistory(commitsHashes);
        history.append(startCommitSha, startFilePath);
        return history;
    }

    private RefComparisonChange createChangeFromPatch(String beforeRef, String afterRef, FilePatch patch) {
        FilePath beforePath = patch.isNewFile() ? null : VcsUtil.getFilePath(myVcsRoot, patch.getBeforeName());
        FilePath afterPath = patch.isDeletedFile() ? null : VcsUtil.getFilePath(myVcsRoot, patch.getAfterName());
        return new RefComparisonChange(new GitRevisionNumber(beforeRef), beforePath, new GitRevisionNumber(afterRef), afterPath);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        GitBranchComparisonResultImpl that = (GitBranchComparisonResultImpl) other;
        return myProject.equals(that.myProject) &&
            myVcsRoot.equals(that.myVcsRoot) &&
            myBaseSha.equals(that.myBaseSha) &&
            myMergeBaseSha.equals(that.myMergeBaseSha) &&
            myHeadSha.equals(that.myHeadSha);
    }

    @Override
    public int hashCode() {
        int result = myProject.hashCode();
        result = 31 * result + myVcsRoot.hashCode();
        result = 31 * result + myBaseSha.hashCode();
        result = 31 * result + myMergeBaseSha.hashCode();
        result = 31 * result + myHeadSha.hashCode();
        return result;
    }
}
