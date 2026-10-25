// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/*
 * Copyright 2013-2026 consulo.io
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

import consulo.application.util.UserHomeFileUtil;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.base.localize.CommonLocalize;
import consulo.project.Project;
import consulo.ui.MessageBoxBuilder;
import consulo.ui.MessageButtonRole;
import consulo.ui.annotation.RequiredUIAccess;
import git4idea.branch.GitBranchUiHandler.CheckoutInOtherWorktreeDecision;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Builds and shows the "branch is already checked out in another worktree" confirmation dialog, shared by
 * every flow that can hit this conflict: checking out a branch and creating a new worktree for a branch
 * ({@code GitCreateWorkingTreeService}).
 */
public final class GitCheckoutInOtherWorktreeDialogs {
    @RequiredUIAccess
    public static CompletableFuture<CheckoutInOtherWorktreeDecision> buildAndShow(Project project,
                                                                                  String branchName,
                                                                                  @Nullable String worktreePath,
                                                                                  LocalizeValue proceedButtonText,
                                                                                  CheckoutInOtherWorktreeButtonSet buttonSet) {
        LocalizeValue title = GitLocalize.branchUiHandlerCheckoutBranchInOtherWorktreeTitle();
        LocalizeValue message = checkoutInOtherWorktreeMessage(branchName, worktreePath);
        LocalizeValue openExistingText = GitLocalize.workingTreeDialogQuickCreateOpenExisting();

        MessageBoxBuilder<CheckoutInOtherWorktreeDecision> builder = MessageBoxBuilder.<CheckoutInOtherWorktreeDecision>create()
            .asQuestion()
            .title(title)
            .text(message);
        switch (buttonSet) {
            case PROCEED_OPEN_EXISTING_OR_CANCEL -> builder
                .button(MessageButtonRole.YES, proceedButtonText, CheckoutInOtherWorktreeDecision.CHECKOUT_ANYWAY)
                .asDefaultButton()
                .button(MessageButtonRole.NO, openExistingText, CheckoutInOtherWorktreeDecision.OPEN_EXISTING_WORKTREE)
                .button(MessageButtonRole.CANCEL, CommonLocalize.buttonCancel(), CheckoutInOtherWorktreeDecision.CANCEL)
                .asExitButton();
            case PROCEED_OR_OPEN_EXISTING -> builder
                .button(MessageButtonRole.YES, proceedButtonText, CheckoutInOtherWorktreeDecision.CHECKOUT_ANYWAY)
                .asDefaultButton()
                .button(MessageButtonRole.NO, openExistingText, CheckoutInOtherWorktreeDecision.OPEN_EXISTING_WORKTREE)
                .asExitButton();
            case PROCEED_OR_CANCEL -> builder
                .button(MessageButtonRole.YES, proceedButtonText, CheckoutInOtherWorktreeDecision.CHECKOUT_ANYWAY)
                .asDefaultButton()
                .button(MessageButtonRole.CANCEL, CommonLocalize.buttonCancel(), CheckoutInOtherWorktreeDecision.CANCEL)
                .asExitButton();
        }
        return builder.showAsync();
    }

    private static LocalizeValue checkoutInOtherWorktreeMessage(String branchName, @Nullable String worktreePath) {
        return worktreePath != null
            ? GitLocalize.branchUiHandlerCheckoutBranchInOtherWorktreeMessageWithPath(branchName, UserHomeFileUtil.getLocationRelativeToUserHome(worktreePath))
            : GitLocalize.branchUiHandlerCheckoutBranchInOtherWorktreeMessage(branchName);
    }

    private GitCheckoutInOtherWorktreeDialogs() {
    }
}
