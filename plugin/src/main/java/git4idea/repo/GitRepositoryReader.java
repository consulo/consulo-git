/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package git4idea.repo;

import consulo.application.util.LineTokenizer;
import consulo.logging.Logger;
import consulo.util.collection.ContainerUtil;
import consulo.util.io.FileUtil;
import consulo.util.lang.Pair;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.repository.RepoStateException;
import consulo.versionControlSystem.distributed.repository.Repository;
import consulo.versionControlSystem.log.Hash;
import consulo.versionControlSystem.log.base.HashImpl;
import git4idea.*;
import git4idea.branch.GitBranchUtil;
import git4idea.validators.GitRefNameValidator;
import org.jetbrains.annotations.NonNls;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static git4idea.GitReference.BRANCH_NAME_HASHING_STRATEGY;
import static java.util.Collections.emptyList;

/**
 * <p>Reads information about the Git repository from Git service files located in the {@code .git} folder.</p>
 * <p>NB: works with {@link File}, i.e. reads from disk. Consider using caching.
 * Throws a {@link RepoStateException} in the case of incorrect Git file format.</p>
 */
public class GitRepositoryReader {

  private static final Logger LOG = Logger.getInstance(GitRepositoryReader.class);

  private static Pattern BRANCH_PATTERN = Pattern.compile(" *(?:ref:)? */?((?:refs/heads/|refs/remotes/)?\\S+)");

  @NonNls
  private static final String REFS_HEADS_PREFIX = "refs/heads/";
  @NonNls
  private static final String REFS_REMOTES_PREFIX = "refs/remotes/";

  @Nonnull
  private final File myHeadFile;       // .git/HEAD
  @Nonnull
  private final File myRefsHeadsDir;   // .git/refs/heads/
  @Nonnull
  private final File myRefsRemotesDir; // .git/refs/remotes/
  @Nonnull
  private final File myPackedRefsFile; // .git/packed-refs
  @Nonnull
  private final GitRepositoryFiles myGitFiles;

  GitRepositoryReader(@Nonnull GitRepositoryFiles gitFiles) {
    myGitFiles = gitFiles;
    myHeadFile = gitFiles.getHeadFile();
    DvcsUtil.assertFileExists(myHeadFile, ".git/HEAD file not found at " + myHeadFile);
    myRefsHeadsDir = gitFiles.getRefsHeadsFile();
    myRefsRemotesDir = gitFiles.getRefsRemotesFile();
    myPackedRefsFile = gitFiles.getPackedRefsPath();
  }

  @Nonnull
  GitBranchState readState(@Nonnull Collection<GitRemote> remotes) {
    Pair<Map<GitLocalBranch, Hash>, Map<GitRemoteBranch, Hash>> branches = readBranches(remotes);
    Map<GitLocalBranch, Hash> localBranches = branches.first;

    HeadInfo headInfo = readHead();
    Repository.State state = readRepositoryState(headInfo);

    GitLocalBranch currentBranch;
    String currentRevision;
    if (!headInfo.isBranch || !localBranches.isEmpty()) {
      currentBranch = findCurrentBranch(headInfo, state, localBranches.keySet());
      currentRevision = getCurrentRevision(headInfo, currentBranch == null ? null : localBranches.get(currentBranch));
    }
    else if (headInfo.content != null) {
      currentBranch = new GitLocalBranch(headInfo.content);
      currentRevision = null;
    }
    else {
      currentBranch = null;
      currentRevision = null;
    }
    if (currentBranch == null && currentRevision == null) {
      LOG.error("Couldn't identify neither current branch nor current revision. .git/HEAD content: [" + headInfo.content + "]");
    }
    return new GitBranchState(currentRevision, currentBranch, state, localBranches, branches.second);
  }

  @Nonnull
  GitHooksInfo readHooksInfo() {
    return new GitHooksInfo(isExistingExecutableFile(myGitFiles.getPreCommitHookFile()),
                            isExistingExecutableFile(myGitFiles.getPrePushHookFile()));
  }

  private static boolean isExistingExecutableFile(@Nonnull File file) {
    return file.exists() && file.canExecute();
  }

  @Nullable
  private static String getCurrentRevision(@Nonnull HeadInfo headInfo, @Nullable Hash currentBranchHash) {
    String currentRevision;
    if (!headInfo.isBranch) {
      currentRevision = headInfo.content;
    }
    else if (currentBranchHash == null) {
      currentRevision = null;
    }
    else {
      currentRevision = currentBranchHash.asString();
    }
    return currentRevision;
  }

  @Nullable
  private GitLocalBranch findCurrentBranch(@Nonnull HeadInfo headInfo,
                                           @Nonnull Repository.State state,
                                           @Nonnull Set<GitLocalBranch> localBranches) {
    final String currentBranchName = findCurrentBranchName(state, headInfo);
    if (currentBranchName == null) {
      return null;
    }
    return ContainerUtil.find(localBranches, branch -> BRANCH_NAME_HASHING_STRATEGY.equals(branch.getFullName(), currentBranchName));
  }

  @Nonnull
  private Repository.State readRepositoryState(@Nonnull HeadInfo headInfo) {
    if (isMergeInProgress()) {
      return Repository.State.MERGING;
    }
    if (isRebaseInProgress()) {
      return Repository.State.REBASING;
    }
    if (!headInfo.isBranch) {
      return Repository.State.DETACHED;
    }
    return Repository.State.NORMAL;
  }

  @Nullable
  private String findCurrentBranchName(@Nonnull Repository.State state, @Nonnull HeadInfo headInfo) {
    String currentBranch = null;
    if (headInfo.isBranch) {
      currentBranch = headInfo.content;
    }
    else if (state == Repository.State.REBASING) {
      currentBranch = readRebaseDirBranchFile(myGitFiles.getRebaseApplyDir());
      if (currentBranch == null) {
        currentBranch = readRebaseDirBranchFile(myGitFiles.getRebaseMergeDir());
      }
    }
    return addRefsHeadsPrefixIfNeeded(currentBranch);
  }

  @Nullable
  private static String readRebaseDirBranchFile(@NonNls File rebaseDir) {
    if (rebaseDir.exists()) {
      File headName = new File(rebaseDir, "head-name");
      if (headName.exists()) {
        return DvcsUtil.tryLoadFileOrReturn(headName, null, StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  @Nullable
  private static String addRefsHeadsPrefixIfNeeded(@Nullable String branchName) {
    if (branchName != null && !branchName.startsWith(REFS_HEADS_PREFIX)) {
      return REFS_HEADS_PREFIX + branchName;
    }
    return branchName;
  }

  private boolean isMergeInProgress() {
    return myGitFiles.getMergeHeadFile().exists();
  }

  private boolean isRebaseInProgress() {
    return myGitFiles.getRebaseApplyDir().exists() || myGitFiles.getRebaseMergeDir().exists();
  }

  @Nonnull
  private Map<String, String> readPackedBranches() {
    if (!myPackedRefsFile.exists()) {
      return Collections.emptyMap();
    }
    try {
      String content = DvcsUtil.tryLoadFile(myPackedRefsFile, StandardCharsets.UTF_8);
      return Arrays.stream(LineTokenizer.tokenize(content, false))
                   .map(it -> GitRepositoryReader.parsePackedRefsLine(it))
                   .filter(Objects::nonNull)
                   .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
    }
    catch (RepoStateException e) {
      return Collections.emptyMap();
    }
  }

  @Nonnull
  private Pair<Map<GitLocalBranch, Hash>, Map<GitRemoteBranch, Hash>> readBranches(@Nonnull Collection<GitRemote> remotes) {
    Map<String, String> data = readBranchRefsFromFiles();
    Map<String, Hash> resolvedRefs = resolveRefs(data);
    return createBranchesFromData(remotes, resolvedRefs);
  }

  @Nonnull
  private Map<String, String> readBranchRefsFromFiles() {
    Map<String, String> result =
      new HashMap<>(readPackedBranches()); // reading from packed-refs first to overwrite values by values from unpacked refs
    result.putAll(readFromBranchFiles(myRefsHeadsDir, REFS_HEADS_PREFIX));
    result.putAll(readFromBranchFiles(myRefsRemotesDir, REFS_REMOTES_PREFIX));
    result.remove(REFS_REMOTES_PREFIX + GitUtil.ORIGIN_HEAD);
    return result;
  }

  @Nonnull
  private static Pair<Map<GitLocalBranch, Hash>, Map<GitRemoteBranch, Hash>> createBranchesFromData(@Nonnull Collection<GitRemote> remotes,
                                                                                                    @Nonnull Map<String, Hash> data) {
    Map<GitLocalBranch, Hash> localBranches = new HashMap<>();
    Map<GitRemoteBranch, Hash> remoteBranches = new HashMap<>();
    for (Map.Entry<String, Hash> entry : data.entrySet()) {
      String refName = entry.getKey();
      Hash hash = entry.getValue();
      if (refName.startsWith(REFS_HEADS_PREFIX)) {
        localBranches.put(new GitLocalBranch(refName), hash);
      }
      else if (refName.startsWith(REFS_REMOTES_PREFIX)) {
        remoteBranches.put(parseRemoteBranch(refName, remotes), hash);
      }
      else {
        LOG.warn("Unexpected ref format: " + refName);
      }
    }
    return Pair.create(localBranches, remoteBranches);
  }

  @Nullable
  private static String loadHashFromBranchFile(@Nonnull File branchFile) {
    return DvcsUtil.tryLoadFileOrReturn(branchFile, null);
  }

  @Nonnull
  private static Map<String, String> readFromBranchFiles(@Nonnull final File refsRootDir, @Nonnull final String prefix) {
    if (!refsRootDir.exists()) {
      return Collections.emptyMap();
    }
    final Map<String, String> result = new HashMap<>();
    FileUtil.processFilesRecursively(refsRootDir, file ->
    {
      if (!file.isDirectory() && !isHidden(file)) {
        String relativePath = FileUtil.getRelativePath(refsRootDir, file);
        if (relativePath != null) {
          String branchName = prefix + FileUtil.toSystemIndependentName(relativePath);
          boolean isBranchNameValid = GitRefNameValidator.getInstance().checkInput(branchName);
          if (isBranchNameValid) {
            String hash = loadHashFromBranchFile(file);
            if (hash != null) {
              result.put(branchName, hash);
            }
          }
        }
      }
      return true;
    }, dir -> !isHidden(dir));
    return result;
  }

  private static boolean isHidden(@Nonnull File file) {
    return file.getName().startsWith(".");
  }

  @Nonnull
  private static GitRemoteBranch parseRemoteBranch(@Nonnull String fullBranchName, @Nonnull Collection<GitRemote> remotes) {
    String stdName = GitBranchUtil.stripRefsPrefix(fullBranchName);

    int slash = stdName.indexOf('/');
    if (slash == -1) { // .git/refs/remotes/my_branch => git-svn
      return new GitSvnRemoteBranch(fullBranchName);
    }
    else {
      GitRemote remote;
      String remoteName;
      String branchName;
      do {
        remoteName = stdName.substring(0, slash);
        branchName = stdName.substring(slash + 1);
        remote = GitUtil.findRemoteByName(remotes, remoteName);
        slash = stdName.indexOf('/', slash + 1);
      }
      while (remote == null && slash >= 0);

      if (remote == null) {
        // user may remove the remote section from .git/config, but leave remote refs untouched in .git/refs/remotes
        LOG.debug(String.format("No remote found with the name [%s]. All remotes: %s", remoteName, remotes));
        GitRemote fakeRemote = new GitRemote(remoteName, emptyList(), emptyList(), emptyList(), emptyList(), emptyList());
        return new GitStandardRemoteBranch(fakeRemote, branchName);
      }
      return new GitStandardRemoteBranch(remote, branchName);
    }
  }

  @Nonnull
  private HeadInfo readHead() {
    String headContent;
    try {
      headContent = DvcsUtil.tryLoadFile(myHeadFile, StandardCharsets.UTF_8);
    }
    catch (RepoStateException e) {
      LOG.error(e);
      return new HeadInfo(false, null);
    }

    Hash hash = parseHash(headContent);
    if (hash != null) {
      return new HeadInfo(false, headContent);
    }
    String target = getTarget(headContent);
    if (target != null) {
      return new HeadInfo(true, target);
    }
    LOG.error(new RepoStateException("Invalid format of the .git/HEAD file: [" + headContent + "]")); // including "refs/tags/v1"
    return new HeadInfo(false, null);
  }

  /**
   * Parses a line from the .git/packed-refs file returning a pair of hash and ref name.
   * Comments and tags are ignored, and null is returned.
   * Incorrectly formatted lines are ignored, a warning is printed to the log, null is returned.
   * A line indicating a hash which an annotated tag (specified in the previous line) points to, is ignored: null is returned.
   */
  @Nullable
  private static Pair<String, String> parsePackedRefsLine(@Nonnull String line) {
    line = line.trim();
    if (line.isEmpty()) {
      return null;
    }
    char firstChar = line.charAt(0);
    if (firstChar == '#') { // ignoring comments
      return null;
    }
    if (firstChar == '^') {
      // ignoring the hash which an annotated tag above points to
      return null;
    }
    String hash = null;
    int i;
    for (i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (!Character.isLetterOrDigit(c)) {
        hash = line.substring(0, i);
        break;
      }
    }
    if (hash == null) {
      LOG.warn("Ignoring invalid packed-refs line: [" + line + "]");
      return null;
    }

    String branch = null;
    int start = i;
    if (start < line.length() && line.charAt(start++) == ' ') {
      for (i = start; i < line.length(); i++) {
        char c = line.charAt(i);
        if (Character.isWhitespace(c)) {
          break;
        }
      }
      branch = line.substring(start, i);
    }

    if (branch == null || !branch.startsWith(REFS_HEADS_PREFIX) && !branch.startsWith(REFS_REMOTES_PREFIX)) {
      return null;
    }
    return Pair.create(shortBuffer(branch), shortBuffer(hash.trim()));
  }

  @Nonnull
  private static String shortBuffer(String raw) {
    return new String(raw);
  }

  @Nonnull
  private static Map<String, Hash> resolveRefs(@Nonnull Map<String, String> data) {
    final Map<String, Hash> resolved = getResolvedHashes(data);
    Map<String, String> unresolved = ContainerUtil.filter(data, refName -> !resolved.containsKey(refName));

    boolean progressed = true;
    while (progressed && !unresolved.isEmpty()) {
      progressed = false;
      for (Iterator<Map.Entry<String, String>> iterator = unresolved.entrySet().iterator(); iterator.hasNext(); ) {
        Map.Entry<String, String> entry = iterator.next();
        String refName = entry.getKey();
        String refValue = entry.getValue();
        String link = getTarget(refValue);
        if (link != null) {
          if (duplicateEntry(resolved, refName, refValue)) {
            iterator.remove();
          }
          else if (!resolved.containsKey(link)) {
            LOG.debug("Unresolved symbolic link [" + refName + "] pointing to [" + refValue + "]"); // transitive link
          }
          else {
            Hash targetValue = resolved.get(link);
            resolved.put(refName, targetValue);
            iterator.remove();
            progressed = true;
          }
        }
        else {
          LOG.warn("Unexpected record [" + refName + "] -> [" + refValue + "]");
          iterator.remove();
        }
      }
    }
    if (!unresolved.isEmpty()) {
      LOG.warn("Cyclic symbolic links among .git/refs: " + unresolved);
    }
    return resolved;
  }

  @Nonnull
  private static Map<String, Hash> getResolvedHashes(@Nonnull Map<String, String> data) {
    Map<String, Hash> resolved = new HashMap<>();
    for (Map.Entry<String, String> entry : data.entrySet()) {
      String refName = entry.getKey();
      Hash hash = parseHash(entry.getValue());
      if (hash != null && !duplicateEntry(resolved, refName, hash)) {
        resolved.put(refName, hash);
      }
    }
    return resolved;
  }

  @Nullable
  private static String getTarget(@Nonnull String refName) {
    Matcher matcher = BRANCH_PATTERN.matcher(refName);
    if (!matcher.matches()) {
      return null;
    }
    String target = matcher.group(1);
    if (!target.startsWith(REFS_HEADS_PREFIX) && !target.startsWith(REFS_REMOTES_PREFIX)) {
      target = REFS_HEADS_PREFIX + target;
    }
    return target;
  }

  @Nullable
  private static Hash parseHash(@Nonnull String value) {
    try {
      return HashImpl.build(value);
    }
    catch (Exception e) {
      return null;
    }
  }

  private static boolean duplicateEntry(@Nonnull Map<String, Hash> resolved, @Nonnull String refName, @Nonnull Object newValue) {
    if (resolved.containsKey(refName)) {
      LOG.error("Duplicate entry for [" + refName + "]. resolved: [" + resolved.get(refName).asString() + "], current: " + newValue + "]");
      return true;
    }
    return false;
  }

  /**
   * Container to hold two information items: current .git/HEAD value and is Git on branch.
   */
  private static class HeadInfo {
    @Nullable
    private final String content;
    private final boolean isBranch;

    HeadInfo(boolean branch, @Nullable String content) {
      isBranch = branch;
      this.content = content;
    }
  }
}
