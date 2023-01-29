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

import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.ChangeListManager;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.i18n.GitBundle;
import git4idea.ui.GitUnstashDialog;
import javax.annotation.Nonnull;

import java.util.List;
import java.util.Set;

/**
 * Git unstash action
 */
public class GitUnstash extends GitRepositoryAction {

  /**
   * {@inheritDoc}
   */
  @Nonnull
  protected String getActionName() {
    return GitBundle.message("unstash.action.name");
  }

  /**
   * {@inheritDoc}
   */
  protected void perform(@Nonnull final Project project,
                         @Nonnull final List<VirtualFile> gitRoots,
                         @Nonnull final VirtualFile defaultRoot,
                         final Set<VirtualFile> affectedRoots,
                         final List<VcsException> exceptions) throws VcsException {
    final ChangeListManager changeListManager = ChangeListManager.getInstance(project);
    if (changeListManager.isFreezedWithNotification("Can not unstash changes now")) return;
    GitUnstashDialog.showUnstashDialog(project, gitRoots, defaultRoot);
  }

  @Override
  protected boolean executeFinalTasksSynchronously() {
    return false;
  }
}
