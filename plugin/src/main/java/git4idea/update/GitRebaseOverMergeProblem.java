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
package git4idea.update;

import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.MessageBoxBuilder;
import consulo.ui.MessageButtonRole;
import consulo.util.lang.ref.Ref;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.log.TimedVcsCommit;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.history.GitHistoryUtils;

import jakarta.annotation.Nonnull;
import java.util.Arrays;
import java.util.function.Consumer;

public class GitRebaseOverMergeProblem {
  private static final Logger LOG = Logger.getInstance(GitRebaseOverMergeProblem.class);
  public static final String DESCRIPTION = "You are about to rebase merge commits. \n" +
    "This can lead to duplicate commits in history, or even data loss.\n" +
    "It is recommended to merge instead of rebase in this case.";

  public enum Decision {
    MERGE_INSTEAD,
    REBASE_ANYWAY,
    CANCEL_OPERATION
  }

  public static boolean hasProblem(@Nonnull Project project,
                                   @Nonnull VirtualFile root,
                                   @Nonnull String baseRef,
                                   @Nonnull String currentRef) {
    final Ref<Boolean> mergeFound = Ref.create(Boolean.FALSE);
    Consumer<TimedVcsCommit> detectingConsumer = commit -> mergeFound.set(true);

    String range = baseRef + ".." + currentRef;
    try {
      GitHistoryUtils.readCommits(project, root, Arrays.asList(range, "--merges"), e -> {
                                  },
                                  e -> {
                                  }, detectingConsumer);
    }
    catch (VcsException e) {
      LOG.warn("Couldn't get git log --merges " + range, e);
    }
    return mergeFound.get();
  }

  @Nonnull
  public static Decision showDialog(@Nonnull Project project) {
    Decision decision = DvcsUtil.showAndWait(project, () -> MessageBoxBuilder.<Decision>create()
      .asWarning()
      .title(LocalizeValue.localizeTODO("Rebasing Merge Commits"))
      .text(LocalizeValue.localizeTODO(DESCRIPTION))
      .button(MessageButtonRole.YES, LocalizeValue.localizeTODO("Merge"), Decision.MERGE_INSTEAD)
      .asDefaultButton()
      .button(MessageButtonRole.NO, LocalizeValue.localizeTODO("Rebase Anyway"), Decision.REBASE_ANYWAY)
      .button(MessageButtonRole.CANCEL, Decision.CANCEL_OPERATION)
      .asExitButton()
      .showAsync(project));
    return decision == null ? Decision.CANCEL_OPERATION : decision;
  }
}
