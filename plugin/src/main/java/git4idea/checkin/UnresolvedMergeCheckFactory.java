/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package git4idea.checkin;

import consulo.annotation.component.ExtensionImpl;
import consulo.application.dumb.DumbAware;
import consulo.application.progress.ProgressIndicator;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.MessageBoxes;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.CommitContext;
import consulo.versionControlSystem.checkin.CheckinHandler;
import consulo.versionControlSystem.checkin.CheckinHandlerFactory;
import consulo.versionControlSystem.checkin.CheckinProjectPanel;
import consulo.versionControlSystem.checkin.CommitCheck;
import consulo.versionControlSystem.checkin.CommitInfo;
import consulo.versionControlSystem.checkin.CommitProblem;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.virtualFileSystem.status.FileStatus;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * Checks if there are unresolved conflicts selected to commit.
 *
 * @author Kirill Likhodedov
 */
@ExtensionImpl
public class UnresolvedMergeCheckFactory extends CheckinHandlerFactory {
  @Nonnull
  @Override
  public CheckinHandler createHandler(final CheckinProjectPanel panel, CommitContext commitContext) {
    return new UnresolvedMergeCheckHandler(panel.getProject());
  }

  private static class UnresolvedMergeCheckHandler extends CheckinHandler implements CommitCheck, DumbAware {
    private final Project myProject;

    private UnresolvedMergeCheckHandler(Project project) {
      myProject = project;
    }

    @Override
    public ExecutionOrder getExecutionOrder() {
      return ExecutionOrder.EARLY;
    }

    @Override
    @RequiredUIAccess
    public boolean isEnabled() {
      return true;
    }

    @Nullable
    @Override
    public CommitProblem runCheck(CommitInfo commitInfo, ProgressIndicator indicator) {
      if (!containsUnresolvedConflicts(commitInfo.getCommittedChanges())) {
        return null;
      }
      Boolean commit = DvcsUtil.showAndWait(myProject, () -> MessageBoxes.yesNo()
        .asWarning()
        .title(LocalizeValue.localizeTODO("Unresolved Conflicts"))
        .text(LocalizeValue.localizeTODO("Are you sure you want to commit changes with unresolved conflicts?"))
        .showAsync(myProject));
      return Boolean.TRUE.equals(commit) ? null : new MergeConflictsCommitProblem();
    }
  }

  private static class MergeConflictsCommitProblem implements CommitProblem {
    @Override
    public LocalizeValue getText() {
      return LocalizeValue.localizeTODO("Unresolved merge conflicts");
    }

    @Override
    @RequiredUIAccess
    public CompletableFuture<CheckinHandler.ReturnResult> showModalSolution(Project project, CommitInfo commitInfo) {
      return CompletableFuture.completedFuture(CheckinHandler.ReturnResult.CANCEL);
    }
  }

  private static boolean containsUnresolvedConflicts(@Nonnull Collection<Change> changes) {
    for (Change change : changes) {
      FileStatus status = change.getFileStatus();
      if (status.equals(FileStatus.MERGE) || status.equals(FileStatus.MERGED_WITH_BOTH_CONFLICTS) ||
          status.equals(FileStatus.MERGED_WITH_CONFLICTS) || status.equals(FileStatus.MERGED_WITH_PROPERTY_CONFLICTS)) {
        return true;
      }
    }
    return false;
  }
}
