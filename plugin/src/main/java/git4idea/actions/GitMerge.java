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
package git4idea.actions;

import consulo.localHistory.Label;
import consulo.localHistory.LocalHistory;
import consulo.project.Project;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.update.ActionInfo;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.GitHandlerUtil;
import git4idea.commands.GitLineHandler;
import git4idea.i18n.GitBundle;
import git4idea.merge.GitMergeDialog;
import git4idea.merge.GitMergeUtil;
import git4idea.repo.GitRepositoryManager;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Git "merge" action
 */
public class GitMerge extends GitRepositoryAction {

  /**
   * {@inheritDoc}
   */
  @Override
  @Nonnull
  protected String getActionName() {
    return GitBundle.message("merge.action.name");
  }

  /**
   * {@inheritDoc}
   */
  protected void perform(@Nonnull final Project project,
                         @Nonnull final List<VirtualFile> gitRoots,
                         @Nonnull final VirtualFile defaultRoot,
                         final Set<VirtualFile> affectedRoots,
                         final List<VcsException> exceptions) throws VcsException {
    GitVcs vcs = GitVcs.getInstance(project);
    if (vcs == null) {
      return;
    }
    GitMergeDialog dialog = new GitMergeDialog(project, gitRoots, defaultRoot);
    try {
      dialog.updateBranches();
    }
    catch (VcsException e) {
      if (vcs.getExecutableValidator().checkExecutableAndShowMessageIfNeeded(null)) {
        vcs.showErrors(Collections.singletonList(e), GitBundle.message("merge.retrieving.branches"));
      }
      return;
    }
    dialog.show();
    if (!dialog.isOK()) {
      return;
    }
    Label beforeLabel = LocalHistory.getInstance().putSystemLabel(project, "Before update");
    GitLineHandler h = dialog.handler();
    final VirtualFile root = dialog.getSelectedRoot();
    affectedRoots.add(root);
    GitRevisionNumber currentRev = GitRevisionNumber.resolve(project, root, "HEAD");
    try {
      GitHandlerUtil.doSynchronously(h, GitBundle.message("merging.title", dialog.getSelectedRoot().getPath()), h.printableCommandLine());
    }
    finally {
      exceptions.addAll(h.errors());
      GitRepositoryManager manager = GitUtil.getRepositoryManager(project);
      manager.updateRepository(root);
    }
    if (exceptions.size() != 0) {
      return;
    }
    GitMergeUtil.showUpdates(this, project, exceptions, root, currentRev, beforeLabel, getActionName(), ActionInfo.INTEGRATE);
  }
}
