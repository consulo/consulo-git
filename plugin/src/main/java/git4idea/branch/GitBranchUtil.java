/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package git4idea.branch;

import com.google.common.collect.Collections2;
import consulo.fileEditor.FileEditorManager;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.ex.awt.Messages;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.*;
import git4idea.commands.GitCommand;
import git4idea.commands.GitSimpleHandler;
import git4idea.config.GitConfigUtil;
import git4idea.config.GitVcsSettings;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitConfig;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.ui.branch.GitMultiRootBranchConfig;
import git4idea.validators.GitNewBranchNameValidator;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.function.Function;

import static consulo.util.lang.ObjectUtil.assertNotNull;

/**
 * @author Kirill Likhodedov
 */
public class GitBranchUtil {
  private static final Logger LOG = Logger.getInstance(GitBranchUtil.class);

  private static final Function<GitBranch, String> BRANCH_TO_NAME = input ->
  {
    assert input != null;
    return input.getName();
  };
  // The name that specifies that git is on specific commit rather then on some branch ({@value})
  private static final String NO_BRANCH_NAME = "(no branch)";

  private GitBranchUtil() {
  }

  /**
   * Returns the tracking information about the given branch in the given repository,
   * or null if there is no such information (i.e. if the branch doesn't have a tracking branch).
   */
  @Nullable
  public static GitBranchTrackInfo getTrackInfoForBranch(@Nonnull GitRepository repository, @Nonnull GitLocalBranch branch) {
    for (GitBranchTrackInfo trackInfo : repository.getBranchTrackInfos()) {
      if (trackInfo.getLocalBranch().equals(branch)) {
        return trackInfo;
      }
    }
    return null;
  }

  @Nullable
  public static GitBranchTrackInfo getTrackInfo(@Nonnull GitRepository repository, @Nonnull String localBranchName) {
    return ContainerUtil.find(repository.getBranchTrackInfos(), it -> it.getLocalBranch().getName().equals(localBranchName));
  }

  @Nonnull
  static String getCurrentBranchOrRev(@Nonnull Collection<GitRepository> repositories) {
    if (repositories.size() > 1) {
      GitMultiRootBranchConfig multiRootBranchConfig = new GitMultiRootBranchConfig(repositories);
      String currentBranch = multiRootBranchConfig.getCurrentBranch();
      LOG.assertTrue(currentBranch != null, "Repositories have unexpectedly diverged. " + multiRootBranchConfig);
      return currentBranch;
    }
    else {
      assert !repositories.isEmpty() : "No repositories passed to GitBranchOperationsProcessor.";
      GitRepository repository = repositories.iterator().next();
      return getBranchNameOrRev(repository);
    }
  }

  @Nonnull
  public static Collection<String> convertBranchesToNames(@Nonnull Collection<? extends GitBranch> branches) {
    return branches.stream().map(BRANCH_TO_NAME).toList();
  }

  /**
   * Returns the current branch in the given repository, or null if either repository is not on the branch, or in case of error.
   *
   * @deprecated Use {@link GitRepository#getCurrentBranch()}
   */
  @Deprecated
  @Nullable
  public static GitLocalBranch getCurrentBranch(@Nonnull Project project, @Nonnull VirtualFile root) {
    GitRepository repository = GitUtil.getRepositoryManager(project).getRepositoryForRoot(root);
    if (repository != null) {
      return repository.getCurrentBranch();
    }
    else {
      LOG.info("getCurrentBranch: Repository is null for root " + root);
      return getCurrentBranchFromGit(project, root);
    }
  }

  @Nullable
  private static GitLocalBranch getCurrentBranchFromGit(@Nonnull Project project, @Nonnull VirtualFile root) {
    GitSimpleHandler handler = new GitSimpleHandler(project, root, GitCommand.REV_PARSE);
    handler.addParameters("--abbrev-ref", "HEAD");
    handler.setSilent(true);
    try {
      String name = handler.run();
      if (!name.equals("HEAD")) {
        return new GitLocalBranch(name);
      }
      else {
        return null;
      }
    }
    catch (VcsException e) {
      LOG.info("git rev-parse --abbrev-ref HEAD", e);
      return null;
    }
  }

  /**
   * Get tracked remote for the branch
   */
  @Nullable
  public static String getTrackedRemoteName(Project project, VirtualFile root, String branchName) throws VcsException {
    return GitConfigUtil.getValue(project, root, trackedRemoteKey(branchName));
  }

  /**
   * Get tracked branch of the given branch
   */
  @Nullable
  public static String getTrackedBranchName(Project project, VirtualFile root, String branchName) throws VcsException {
    return GitConfigUtil.getValue(project, root, trackedBranchKey(branchName));
  }

  @Nonnull
  private static String trackedBranchKey(String branchName) {
    return "branch." + branchName + ".merge";
  }

  @Nonnull
  private static String trackedRemoteKey(String branchName) {
    return "branch." + branchName + ".remote";
  }

  /**
   * Get the tracking branch for the given branch, or null if the given branch doesn't track anything.
   *
   * @deprecated Use {@link GitConfig#getBranchTrackInfos()}
   */
  @Deprecated
  @Nullable
  public static GitRemoteBranch tracked(@Nonnull Project project,
                                        @Nonnull VirtualFile root,
                                        @Nonnull String branchName) throws VcsException {
    final HashMap<String, String> result = new HashMap<>();
    GitConfigUtil.getValues(project, root, null, result);
    String remoteName = result.get(trackedRemoteKey(branchName));
    if (remoteName == null) {
      return null;
    }
    String branch = result.get(trackedBranchKey(branchName));
    if (branch == null) {
      return null;
    }

    if (".".equals(remoteName)) {
      return new GitSvnRemoteBranch(branch);
    }

    GitRemote remote = findRemoteByNameOrLogError(project, root, remoteName);
    if (remote == null) {
      return null;
    }
    return new GitStandardRemoteBranch(remote, branch);
  }

  @Nullable
  @Deprecated
  public static GitRemote findRemoteByNameOrLogError(@Nonnull Project project, @Nonnull VirtualFile root, @Nonnull String remoteName) {
    GitRepository repository = GitUtil.getRepositoryForRootOrLogError(project, root);
    if (repository == null) {
      return null;
    }

    GitRemote remote = GitUtil.findRemoteByName(repository, remoteName);
    if (remote == null) {
      LOG.warn("Couldn't find remote with name " + remoteName);
      return null;
    }
    return remote;
  }

  /**
   * Convert {@link GitRemoteBranch GitRemoteBranches} to their names, and remove remote HEAD pointers: origin/HEAD.
   */
  @Nonnull
  public static Collection<String> getBranchNamesWithoutRemoteHead(@Nonnull Collection<GitRemoteBranch> remoteBranches) {
    return Collections2.filter(convertBranchesToNames(remoteBranches), input -> {
      assert input != null;
      return !input.equals("HEAD");
    });
  }

  @Nonnull
  public static String stripRefsPrefix(@Nonnull String branchName) {
    if (branchName.startsWith(GitBranch.REFS_HEADS_PREFIX)) {
      return branchName.substring(GitBranch.REFS_HEADS_PREFIX.length());
    }
    else if (branchName.startsWith(GitBranch.REFS_REMOTES_PREFIX)) {
      return branchName.substring(GitBranch.REFS_REMOTES_PREFIX.length());
    }
    else if (branchName.startsWith(GitTag.REFS_TAGS_PREFIX)) {
      return branchName.substring(GitTag.REFS_TAGS_PREFIX.length());
    }
    return branchName;
  }

  /**
   * Returns current branch name (if on branch) or current revision otherwise.
   * For fresh repository returns an empty string.
   */
  @Nonnull
  public static String getBranchNameOrRev(@Nonnull GitRepository repository) {
    if (repository.isOnBranch()) {
      GitBranch currentBranch = repository.getCurrentBranch();
      assert currentBranch != null;
      return currentBranch.getName();
    }
    else {
      String currentRevision = repository.getCurrentRevision();
      return currentRevision != null ? currentRevision.substring(0, 7) : "";
    }
  }

  /**
   * Shows a message dialog to enter the name of new branch.
   *
   * @return name of new branch or {@code null} if user has cancelled the dialog.
   */
  @Nullable
  public static String getNewBranchNameFromUser(@Nonnull Project project,
                                                @Nonnull Collection<GitRepository> repositories,
                                                @Nonnull String dialogTitle) {
    return Messages.showInputDialog(project,
                                    "Enter the name of new branch:",
                                    dialogTitle,
                                    Messages.getQuestionIcon(),
                                    "",
                                    GitNewBranchNameValidator.newInstance(repositories));
  }

  /**
   * Returns the text that is displaying current branch.
   * In the simple case it is just the branch name, but in detached HEAD state it displays the hash or "rebasing master".
   */
  @Nonnull
  public static String getDisplayableBranchText(@Nonnull GitRepository repository) {
    GitRepository.State state = repository.getState();
    if (state == GitRepository.State.DETACHED) {
      String currentRevision = repository.getCurrentRevision();
      assert currentRevision != null : "Current revision can't be null in DETACHED state, only on the fresh repository.";
      return DvcsUtil.getShortHash(currentRevision);
    }

    String prefix = "";
    if (state == GitRepository.State.MERGING || state == GitRepository.State.REBASING) {
      prefix = state.toString() + " ";
    }

    GitBranch branch = repository.getCurrentBranch();
    String branchName = (branch == null ? "" : branch.getName());
    return prefix + branchName;
  }

  /**
   * Guesses the Git root on which a Git action is to be invoked.
   * <ol>
   * <li>
   * Returns the root for the selected file. Selected file is determined by {@link DvcsUtil#getSelectedFile(Project)}.
   * If selected file is unknown (for example, no file is selected in the Project View or Changes View and no file is open in the editor),
   * continues guessing. Otherwise returns the Git root for the selected file. If the file is not under a known Git root,
   * but there is at least one git root,  continues guessing, otherwise
   * <code>null</code> will be returned - the file is definitely determined, but it is not under Git and no git roots exists in project.
   * </li>
   * <li>
   * Takes all Git roots registered in the Project. If there is only one, it is returned.
   * </li>
   * <li>
   * If there are several Git roots,
   * </li>
   * </ol>
   * <p>
   * <p>
   * NB: This method has to be accessed from the <b>read action</b>, because it may query
   * {@link FileEditorManager#getSelectedTextEditor()}.
   * </p>
   *
   * @param project current project
   * @return Git root that may be considered as "current".
   * <code>null</code> is returned if a file not under Git was explicitly selected, if there are no Git roots in the project,
   * or if the current Git root couldn't be determined.
   */
  @Nullable
  public static GitRepository getCurrentRepository(@Nonnull Project project) {
    return getRepositoryOrGuess(project, DvcsUtil.getSelectedFile(project));
  }

  @Nullable
  public static GitRepository getRepositoryOrGuess(@Nonnull Project project, @Nullable VirtualFile file) {
    if (project.isDisposed()) {
      return null;
    }
    return DvcsUtil.guessRepositoryForFile(project,
                                           GitUtil.getRepositoryManager(project),
                                           file,
                                           GitVcsSettings.getInstance(project).getRecentRootPath());
  }

  @Nonnull
  public static Collection<String> getCommonBranches(Collection<GitRepository> repositories, boolean local) {
    Collection<String> commonBranches = null;
    for (GitRepository repository : repositories) {
      GitBranchesCollection branchesCollection = repository.getBranches();

      Collection<String> names =
        local ? convertBranchesToNames(branchesCollection.getLocalBranches()) : getBranchNamesWithoutRemoteHead(branchesCollection.getRemoteBranches());
      if (commonBranches == null) {
        commonBranches = names;
      }
      else {
        commonBranches = ContainerUtil.intersection(commonBranches, names);
      }
    }

    if (commonBranches != null) {
      ArrayList<String> common = new ArrayList<>(commonBranches);
      Collections.sort(common);
      return common;
    }
    else {
      return Collections.emptyList();
    }
  }

  /**
   * List branches containing a commit. Specify null if no commit filtering is needed.
   */
  @Nonnull
  public static Collection<String> getBranches(@Nonnull Project project,
                                               @Nonnull VirtualFile root,
                                               boolean localWanted,
                                               boolean remoteWanted,
                                               @Nullable String containingCommit) throws VcsException {
    // preparing native command executor
    final GitSimpleHandler handler = new GitSimpleHandler(project, root, GitCommand.BRANCH);
    handler.setSilent(true);
    handler.addParameters("--no-color");
    boolean remoteOnly = false;
    if (remoteWanted && localWanted) {
      handler.addParameters("-a");
      remoteOnly = false;
    }
    else if (remoteWanted) {
      handler.addParameters("-r");
      remoteOnly = true;
    }
    if (containingCommit != null) {
      handler.addParameters("--contains", containingCommit);
    }
    final String output = handler.run();

    if (output.trim().length() == 0) {
      // the case after git init and before first commit - there is no branch and no output, and we'll take refs/heads/master
      String head;
      try {
        File headFile = assertNotNull(GitUtil.getRepositoryManager(project).getRepositoryForRoot(root)).getRepositoryFiles().getHeadFile();
        head = Files.readString(headFile.toPath(), StandardCharsets.UTF_8).trim();
        final String prefix = "ref: refs/heads/";
        return head.startsWith(prefix) ? Collections.singletonList(head.substring(prefix.length())) : Collections.<String>emptyList();
      }
      catch (IOException e) {
        LOG.info(e);
        return Collections.emptyList();
      }
    }

    Collection<String> branches = ContainerUtil.newArrayList();
    // standard situation. output example:
    //  master
    //* my_feature
    //  remotes/origin/HEAD -> origin/master
    //  remotes/origin/eap
    //  remotes/origin/feature
    //  remotes/origin/master
    // also possible:
    //* (no branch)
    // and if we call with -r instead of -a, remotes/ prefix is omitted:
    // origin/HEAD -> origin/master
    final String[] split = output.split("\n");
    for (String b : split) {
      b = b.substring(2).trim();
      if (b.equals(NO_BRANCH_NAME)) {
        continue;
      }

      String remotePrefix = null;
      if (b.startsWith("remotes/")) {
        remotePrefix = "remotes/";
      }
      else if (b.startsWith(GitBranch.REFS_REMOTES_PREFIX)) {
        remotePrefix = GitBranch.REFS_REMOTES_PREFIX;
      }
      boolean isRemote = remotePrefix != null || remoteOnly;
      if (isRemote) {
        if (!remoteOnly) {
          b = b.substring(remotePrefix.length());
        }
        final int idx = b.indexOf("HEAD ->");
        if (idx > 0) {
          continue;
        }
      }
      branches.add(b);
    }
    return branches;
  }
}
