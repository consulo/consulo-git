// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote.hosting.ui;

import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.MessageBoxBuilder;
import consulo.ui.MessageButtonRole;
import consulo.ui.annotation.RequiredUIAccess;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

public final class ResolveConflictsLocallyDialogComponentFactory {
    private ResolveConflictsLocallyDialogComponentFactory() {
    }

    private static LocalizeValue toMessage(ResolveConflictsMethod method) {
        return switch (method) {
            case REBASE -> GitLocalize.rebasingMergeCommitsButtonRebase();
            case MERGE -> GitLocalize.rebasingMergeCommitsButtonMerge();
        };
    }

    /**
     * Shows a dialog to choose between rebasing or merging a branch with conflicts known on the remote.
     */
    @RequiredUIAccess
    public static CompletableFuture<@Nullable ResolveConflictsMethod> showBranchUpdateDialog(Project project,
                                                                                             String headRefName,
                                                                                             String baseRefName) {
        return MessageBoxBuilder.<@Nullable ResolveConflictsMethod>create()
            .asQuestion()
            .title(GitLocalize.dialogTitleUpdateBranch(headRefName))
            .text(GitLocalize.dialogMessageUpdateBranch(headRefName, baseRefName))
            .button(MessageButtonRole.YES, toMessage(ResolveConflictsMethod.REBASE), ResolveConflictsMethod.REBASE)
            .asDefaultButton()
            .button(MessageButtonRole.NO, toMessage(ResolveConflictsMethod.MERGE), ResolveConflictsMethod.MERGE)
            .button(MessageButtonRole.CANCEL, GitLocalize.rebasingMergeCommitsButtonCancel(), (ResolveConflictsMethod) null)
            .asExitButton()
            .showAsync(project);
    }
}
