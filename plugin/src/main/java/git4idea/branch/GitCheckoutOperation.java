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

import consulo.application.AccessToken;
import consulo.application.progress.ProgressIndicator;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.event.NotificationListener;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.event.HyperlinkEvent;
import consulo.util.collection.ArrayUtil;
import consulo.util.lang.Pair;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitStandardLocalBranch;
import git4idea.GitUtil;
import git4idea.GitWorkingTree;
import git4idea.commands.*;
import git4idea.config.GitVcsSettings;
import git4idea.repo.GitRepository;
import git4idea.util.GitPreservingProcess;
import git4idea.workingTrees.GitWorkingTreesService;
import git4idea.workingTrees.GitWorkingTreesUtil;
import git4idea.workingTrees.actions.GitChooseWorkingTreeDialog;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static git4idea.util.GitUIUtil.code;

/**
 * Represents {@code git checkout} operation.
 * Fails to checkout if there are unmerged files.
 * Fails to checkout if there are untracked files that would be overwritten by checkout. Shows the list of files.
 * If there are local changes that would be overwritten by checkout, proposes to perform a "smart checkout" which means stashing local
 * changes, checking out, and then unstashing the changes back (possibly with showing the conflict resolving dialog).
 *
 * @author Kirill Likhodedov
 */
class GitCheckoutOperation extends GitBranchOperation {
    public static final String ROLLBACK_PROPOSAL_FORMAT =
        "You may rollback (checkout back to previous branch) not to let branches diverge.";

    @Nonnull
    private final String myStartPointReference;
    private final boolean myDetach;
    private final boolean myReset;
    private final boolean myRefShouldBeValid;
    @Nullable
    private final String myNewBranch;

    /**
     * Repositories for which the user chose to open the already existing worktree instead of checking out.
     * They are neither successful nor skipped, so they are tracked separately to avoid misreporting them.
     */
    private final Collection<GitRepository> myOpenedInOtherWorktreeRepositories = new ArrayList<>();

    /**
     * Repositories for which the user canceled the worktree-conflict dialog outright, declining both to check out
     * here and to open the other worktree. A deliberate cancel is not a failure, so no notification follows it.
     */
    private final Collection<GitRepository> myCancelledWorktreeConflictRepositories = new ArrayList<>();

    private enum OtherWorktreeConflictOutcome {
        SUCCESS,
        OPENED_EXISTING_WORKTREE,
        CANCELLED,
        FATAL_ERROR
    }

    /**
     * Outcome of {@link #runCheckoutAndHandleCommonFailures}: {@code UNHANDLED} means none of the three detectors
     * common to every checkout attempt fired, leaving the caller to interpret its own attempt-specific detectors.
     */
    private enum CheckoutAttemptOutcome {
        SUCCESS,
        FATAL_ERROR,
        UNHANDLED
    }

    GitCheckoutOperation(
        @Nonnull Project project,
        @Nonnull Git git,
        @Nonnull GitBranchUiHandler uiHandler,
        @Nonnull Collection<GitRepository> repositories,
        @Nonnull String startPointReference,
        boolean detach,
        boolean withReset,
        boolean refShouldBeValid,
        @Nullable String newBranch
    ) {
        super(project, git, uiHandler, repositories);
        myStartPointReference = startPointReference;
        myDetach = detach;
        myReset = withReset;
        myRefShouldBeValid = refShouldBeValid;
        myNewBranch = newBranch;
    }

    @Override
    @RequiredUIAccess
    protected void execute() {
        saveAllDocuments();
        boolean fatalErrorHappened = false;
        try (AccessToken token = DvcsUtil.workingTreeChangeStarted(myProject, getOperationName().get())) {
            while (hasMoreRepositories() && !fatalErrorHappened) {
                GitRepository repository = next();

                VirtualFile root = repository.getRoot();
                GitLocalChangesWouldBeOverwrittenDetector localChangesDetector =
                    new GitLocalChangesWouldBeOverwrittenDetector(root, GitLocalChangesWouldBeOverwrittenDetector.Operation.CHECKOUT);
                GitSimpleEventDetector unmergedFiles =
                    new GitSimpleEventDetector(GitSimpleEventDetector.Event.UNMERGED_PREVENTING_CHECKOUT);
                GitSimpleEventDetector unknownPathspec = new GitSimpleEventDetector(GitSimpleEventDetector.Event.INVALID_REFERENCE);
                GitUntrackedFilesOverwrittenByOperationDetector untrackedOverwrittenByCheckout =
                    new GitUntrackedFilesOverwrittenByOperationDetector(root);
                GitBranchAlreadyCheckedOutInOtherWorktreeDetector otherWorktreeDetector =
                    new GitBranchAlreadyCheckedOutInOtherWorktreeDetector();

                GitCommandResult result = myGit.checkout(
                    repository,
                    myStartPointReference,
                    myNewBranch,
                    false,
                    myDetach,
                    myReset,
                    false,
                    localChangesDetector,
                    unmergedFiles,
                    unknownPathspec,
                    untrackedOverwrittenByCheckout,
                    otherWorktreeDetector
                );

                CheckoutAttemptOutcome outcome =
                    runCheckoutAndHandleCommonFailures(repository, result, localChangesDetector, unmergedFiles, untrackedOverwrittenByCheckout);
                if (outcome == CheckoutAttemptOutcome.FATAL_ERROR) {
                    fatalErrorHappened = true;
                }
                else if (outcome == CheckoutAttemptOutcome.UNHANDLED) {
                    if (!myRefShouldBeValid && unknownPathspec.hasHappened()) {
                        markSkip(repository);
                    }
                    else if (otherWorktreeDetector.isDetected()) {
                        OtherWorktreeConflictOutcome otherWorktreeOutcome =
                            checkoutIgnoringOtherWorktreeOrNotify(repository, result, otherWorktreeDetector);
                        if (otherWorktreeOutcome == OtherWorktreeConflictOutcome.FATAL_ERROR) {
                            fatalErrorHappened = true;
                        }
                        else if (otherWorktreeOutcome == OtherWorktreeConflictOutcome.OPENED_EXISTING_WORKTREE) {
                            handleOpenedInOtherWorktree(repository);
                        }
                        else if (otherWorktreeOutcome == OtherWorktreeConflictOutcome.CANCELLED) {
                            handleCancelledWorktreeConflict(repository);
                        }
                    }
                    else {
                        fatalError(getCommonErrorTitle(), result.getErrorOutputAsJoinedValue());
                        fatalErrorHappened = true;
                    }
                }
            }
        }

        if (!fatalErrorHappened) {
            if (wereSuccessful()) {
                if (!wereSkipped()) {
                    notifySuccess();
                    updateRecentBranch();
                }
                else {
                    String mentionSuccess = getSuccessMessage() + GitUtil.mention(getSuccessfulRepositories(), 4);
                    String mentionSkipped = wereSkipped() ? "<br>Revision not found" + GitUtil.mention(getSkippedRepositories(), 4) : "";

                    myNotificationService.newInfo(VcsNotifier.NOTIFICATION_GROUP_ID)
                        .content(LocalizeValue.localizeTODO(mentionSuccess + mentionSkipped + "<br><a href='rollback'>Rollback</a>"))
                        .hyperlinkListener(new RollbackOperationNotificationListener())
                        .notify(myProject);
                    updateRecentBranch();
                }
            }
            else if (!myOpenedInOtherWorktreeRepositories.isEmpty() || !myCancelledWorktreeConflictRepositories.isEmpty()) {
                // the user resolved a worktree conflict without checking out, by opening the other worktree or by canceling outright;
                // other repositories may still have been skipped for an unrelated reason (invalid ref) and that must still be reported.
                if (wereSkipped()) {
                    notifyError(
                        LocalizeValue.localizeTODO("Couldn't checkout " + myStartPointReference),
                        LocalizeValue.localizeTODO("Revision not found" + GitUtil.mention(getSkippedRepositories(), 4))
                    );
                }
            }
            else {
                LOG.assertTrue(!myRefShouldBeValid);
                notifyError(
                    LocalizeValue.localizeTODO("Couldn't checkout " + myStartPointReference),
                    LocalizeValue.localizeTODO("Revision not found" + GitUtil.mention(getSkippedRepositories(), 4))
                );
            }
        }
    }

    /**
     * Interprets a checkout attempt's result against the three detectors common to every checkout attempt (local
     * changes, unmerged files, untracked files that would be overwritten) and applies the matching recovery (VFS
     * refresh, smart checkout, fatal error dialogs). Returns {@link CheckoutAttemptOutcome#UNHANDLED} when none of
     * these common cases apply, leaving the caller to interpret its own attempt-specific detectors.
     */
    private CheckoutAttemptOutcome runCheckoutAndHandleCommonFailures(
        GitRepository repository,
        GitCommandResult result,
        GitLocalChangesWouldBeOverwrittenDetector localChangesDetector,
        GitSimpleEventDetector unmergedFiles,
        GitUntrackedFilesOverwrittenByOperationDetector untrackedOverwrittenByCheckout
    ) {
        if (result.success()) {
            refresh(repository);
            markSuccessful(repository);
            return CheckoutAttemptOutcome.SUCCESS;
        }
        if (unmergedFiles.hasHappened()) {
            fatalUnmergedFilesError();
            return CheckoutAttemptOutcome.FATAL_ERROR;
        }
        if (localChangesDetector.wasMessageDetected()) {
            boolean smartCheckoutSucceeded = smartCheckoutOrNotify(repository, localChangesDetector);
            return smartCheckoutSucceeded ? CheckoutAttemptOutcome.SUCCESS : CheckoutAttemptOutcome.FATAL_ERROR;
        }
        if (untrackedOverwrittenByCheckout.wasMessageDetected()) {
            fatalUntrackedFilesError(repository.getRoot(), untrackedOverwrittenByCheckout.getRelativeFilePaths());
            return CheckoutAttemptOutcome.FATAL_ERROR;
        }
        return CheckoutAttemptOutcome.UNHANDLED;
    }

    /**
     * Marks the repository as handled by opening its existing worktree instead of checking it out here, so it is
     * removed from further processing without being counted as either successful or skipped.
     */
    private void handleOpenedInOtherWorktree(GitRepository repository) {
        myOpenedInOtherWorktreeRepositories.add(repository);
        markHandledExternally(repository);
    }

    /**
     * Marks the repository as handled by the user's outright cancel of the worktree-conflict dialog, so it is
     * removed from further processing without being counted as either successful or skipped.
     */
    private void handleCancelledWorktreeConflict(GitRepository repository) {
        myCancelledWorktreeConflictRepositories.add(repository);
        markHandledExternally(repository);
    }

    /**
     * Handles the "branch is already checked out in another worktree" conflict: asks the user for confirmation and, if confirmed,
     * retries the checkout with {@code --ignore-other-worktrees}. The retry keeps watching for local changes / unmerged / untracked
     * files, same as the original checkout attempt, so a conflict discovered only on retry still goes through the usual recovery
     * (smart checkout, fatal error dialogs) instead of falling straight to a generic error.
     */
    private OtherWorktreeConflictOutcome checkoutIgnoringOtherWorktreeOrNotify(GitRepository repository,
                                                                               GitCommandResult failedResult,
                                                                               GitBranchAlreadyCheckedOutInOtherWorktreeDetector detector) {
        GitBranchUiHandler.CheckoutInOtherWorktreeDecision decision =
            showCheckoutInOtherWorktreeDialog(repository, detector, myStartPointReference);
        if (decision == GitBranchUiHandler.CheckoutInOtherWorktreeDecision.OPEN_EXISTING_WORKTREE) {
            if (openExistingWorktree(repository, detector.getMatch())) {
                return OtherWorktreeConflictOutcome.OPENED_EXISTING_WORKTREE;
            }
            // The user picked "Open Existing Worktree" but then backed out of the worktree chooser: nothing opened,
            // so this must not be reported as the success-adjacent OPENED_EXISTING_WORKTREE outcome either.
            fatalError(getCommonErrorTitle(), failedResult.getErrorOutputAsJoinedValue());
            return OtherWorktreeConflictOutcome.FATAL_ERROR;
        }
        if (decision != GitBranchUiHandler.CheckoutInOtherWorktreeDecision.CHECKOUT_ANYWAY) {
            // The user canceled the dialog outright: a deliberate "do nothing" for this repository, not an error.
            return OtherWorktreeConflictOutcome.CANCELLED;
        }

        VirtualFile root = repository.getRoot();
        GitLocalChangesWouldBeOverwrittenDetector localChangesDetector =
            new GitLocalChangesWouldBeOverwrittenDetector(root, GitLocalChangesWouldBeOverwrittenDetector.Operation.CHECKOUT);
        GitSimpleEventDetector unmergedFiles = new GitSimpleEventDetector(GitSimpleEventDetector.Event.UNMERGED_PREVENTING_CHECKOUT);
        GitUntrackedFilesOverwrittenByOperationDetector untrackedOverwrittenByCheckout =
            new GitUntrackedFilesOverwrittenByOperationDetector(root);
        GitCommandResult retryResult = myGit.checkout(repository, myStartPointReference, myNewBranch, false, myDetach, myReset, true,
            localChangesDetector, unmergedFiles, untrackedOverwrittenByCheckout);
        CheckoutAttemptOutcome outcome =
            runCheckoutAndHandleCommonFailures(repository, retryResult, localChangesDetector, unmergedFiles, untrackedOverwrittenByCheckout);
        if (outcome == CheckoutAttemptOutcome.SUCCESS) {
            return OtherWorktreeConflictOutcome.SUCCESS;
        }
        if (outcome == CheckoutAttemptOutcome.FATAL_ERROR) {
            return OtherWorktreeConflictOutcome.FATAL_ERROR;
        }
        fatalError(getCommonErrorTitle(), retryResult.getErrorOutputAsJoinedValue());
        return OtherWorktreeConflictOutcome.FATAL_ERROR;
    }

    private GitBranchUiHandler.CheckoutInOtherWorktreeDecision showCheckoutInOtherWorktreeDialog(
        GitRepository repository,
        GitBranchAlreadyCheckedOutInOtherWorktreeDetector detector,
        String reference
    ) {
        GitBranchAlreadyCheckedOutInOtherWorktreeMatch match = detector.getMatch();
        String branchName = match != null ? match.branchName() : reference;
        return myUiHandler.showCheckoutBranchInOtherWorktreeDialog(branchName, match != null ? worktreePathToMention(repository, match) : null);
    }

    /**
     * The worktree path to name in the conflict dialog, or {@code null} when the repository model already knows
     * of more than one worktree with the branch checked out, so the message does not single out just one of them.
     */
    private static @Nullable String worktreePathToMention(GitRepository repository, GitBranchAlreadyCheckedOutInOtherWorktreeMatch match) {
        List<GitWorkingTree> checkedOutWorkingTrees = findCheckedOutWorkingTrees(repository, match);
        return checkedOutWorkingTrees.size() > 1 ? null : match.worktreePath();
    }

    /**
     * Opens every worktree known to have the branch checked out, and lets the user choose when there is more
     * than one. Falls back to the single path git reported when the repository model knows none, for example
     * when the working trees feature is off.
     *
     * @return {@code true} if a worktree was actually opened; {@code false} if there was nothing to open or the
     * user backed out of the chooser, in which case the caller must not treat this as a handled conflict.
     */
    private boolean openExistingWorktree(GitRepository repository, @Nullable GitBranchAlreadyCheckedOutInOtherWorktreeMatch match) {
        if (match == null) {
            return false;
        }
        List<GitWorkingTree> checkedOutWorkingTrees = findCheckedOutWorkingTrees(repository, match);
        GitWorkingTree tree;
        if (checkedOutWorkingTrees.isEmpty()) {
            String worktreePath = match.worktreePath();
            if (worktreePath == null) {
                return false;
            }
            tree = GitWorkingTree.pathOnly(worktreePath);
        }
        else {
            tree = chooseWorkingTreeToOpen(checkedOutWorkingTrees);
            if (tree == null) {
                return false;
            }
        }
        GitWorkingTreesService.getInstance(myProject).openWorkingTreeProject(tree, null);
        return true;
    }

    private static List<GitWorkingTree> findCheckedOutWorkingTrees(GitRepository repository, GitBranchAlreadyCheckedOutInOtherWorktreeMatch match) {
        return GitWorkingTreesUtil.findCheckedOutWorkingTrees(
            new GitStandardLocalBranch(match.branchName()), repository.getWorkingTreeHolder().getWorkingTrees(), false);
    }

    /**
     * {@code workingTrees}' only entry, or the one the user picks from a chooser dialog, or {@code null} if they cancel it.
     */
    private @Nullable GitWorkingTree chooseWorkingTreeToOpen(List<GitWorkingTree> workingTrees) {
        return CompletableFuture.supplyAsync(() -> GitChooseWorkingTreeDialog.chooseAmong(myProject, workingTrees), myProject.getUIAccess())
            .thenCompose(future -> future)
            .join();
    }

    private boolean smartCheckoutOrNotify(
        @Nonnull GitRepository repository,
        @Nonnull GitMessageWithFilesDetector localChangesOverwrittenByCheckout
    ) {
        Pair<List<GitRepository>, List<Change>> conflictingRepositoriesAndAffectedChanges =
            getConflictingRepositoriesAndAffectedChanges(repository, localChangesOverwrittenByCheckout,
                myCurrentHeads.get(repository), myStartPointReference
            );
        List<GitRepository> allConflictingRepositories = conflictingRepositoriesAndAffectedChanges.getFirst();
        List<Change> affectedChanges = conflictingRepositoriesAndAffectedChanges.getSecond();

        Collection<String> absolutePaths =
            GitUtil.toAbsolute(repository.getRoot(), localChangesOverwrittenByCheckout.getRelativeFilePaths());
        int smartCheckoutDecision =
            myUiHandler.showSmartOperationDialog(myProject, affectedChanges, absolutePaths, "checkout", "&Force Checkout");
        if (smartCheckoutDecision == GitSmartOperationDialog.SMART_EXIT_CODE) {
            Collection<GitRepository> openedInOtherWorktree = new ArrayList<>();
            boolean smartCheckedOutSuccessfully =
                smartCheckout(allConflictingRepositories, myStartPointReference, myNewBranch, getIndicator(), openedInOtherWorktree);
            if (smartCheckedOutSuccessfully) {
                for (GitRepository conflictingRepository : allConflictingRepositories) {
                    if (openedInOtherWorktree.contains(conflictingRepository)) {
                        handleOpenedInOtherWorktree(conflictingRepository);
                        continue;
                    }
                    markSuccessful(conflictingRepository);
                    refresh(conflictingRepository);
                }
                return true;
            }
            else {
                // notification is handled in smartCheckout()
                return false;
            }
        }
        else if (smartCheckoutDecision == GitSmartOperationDialog.FORCE_EXIT_CODE) {
            Collection<GitRepository> openedInOtherWorktree = new ArrayList<>();
            boolean forceCheckoutSucceeded =
                checkoutOrNotify(allConflictingRepositories, myStartPointReference, myNewBranch, true, openedInOtherWorktree);
            if (forceCheckoutSucceeded) {
                openedInOtherWorktree.forEach(this::handleOpenedInOtherWorktree);
                List<GitRepository> checkedOutRepositories = new ArrayList<>(allConflictingRepositories);
                checkedOutRepositories.removeAll(openedInOtherWorktree);
                markSuccessful(ArrayUtil.toObjectArray(checkedOutRepositories, GitRepository.class));
                refresh(ArrayUtil.toObjectArray(checkedOutRepositories, GitRepository.class));
            }
            return forceCheckoutSucceeded;
        }
        else {
            fatalLocalChangesError(myStartPointReference);
            return false;
        }
    }

    @Nonnull
    @Override
    protected LocalizeValue getRollbackProposal() {
        return LocalizeValue.localizeTODO(
            "However checkout has succeeded for the following " + repositories() + ":<br/>" +
                successfulRepositoriesJoined() + "<br/>" + ROLLBACK_PROPOSAL_FORMAT
        );
    }

    @Nonnull
    @Override
    protected LocalizeValue getOperationName() {
        return LocalizeValue.localizeTODO("checkout");
    }

    @Override
    protected void rollback() {
        GitCompoundResult checkoutResult = new GitCompoundResult(myProject);
        GitCompoundResult deleteResult = new GitCompoundResult(myProject);
        for (GitRepository repository : getSuccessfulRepositories()) {
            GitCommandResult result = myGit.checkout(repository, myCurrentHeads.get(repository), null, true, false);
            checkoutResult.append(repository, result);
            if (result.success() && myNewBranch != null) {
                /*
                  force delete is needed, because we create new branch from branch other that the current one
                  e.g. being on master create newBranch from feature,
                  then rollback => newBranch is not fully merged to master (although it is obviously fully merged to feature).
                 */
                deleteResult.append(repository, myGit.branchDelete(repository, myNewBranch, true));
            }
            refresh(repository);
        }
        if (!checkoutResult.totalSuccess() || !deleteResult.totalSuccess()) {
            StringBuilder message = new StringBuilder();
            if (!checkoutResult.totalSuccess()) {
                message.append("Errors during checkout: ");
                message.append(checkoutResult.getErrorOutputWithReposIndication());
            }
            if (!deleteResult.totalSuccess()) {
                message.append("Errors during deleting ").append(code(myNewBranch)).append(": ");
                message.append(deleteResult.getErrorOutputWithReposIndication());
            }
            myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Error during rollback"))
                .content(LocalizeValue.localizeTODO(message.toString()))
                .notify(myProject);
        }
    }

    @Nonnull
    private LocalizeValue getCommonErrorTitle() {
        return LocalizeValue.localizeTODO("Couldn't checkout " + myStartPointReference);
    }

    @Nonnull
    @Override
    public LocalizeValue getSuccessMessage() {
        if (myNewBranch == null) {
            return LocalizeValue.localizeTODO(String.format("Checked out <b><code>%s</code></b>", myStartPointReference));
        }
        return LocalizeValue.localizeTODO(String.format(
            "Checked out new branch <b><code>%s</code></b> from <b><code>%s</code></b>",
            myNewBranch,
            myStartPointReference
        ));
    }

    // stash - checkout - unstash
    private boolean smartCheckout(
        @Nonnull List<GitRepository> repositories,
        @Nonnull String reference,
        @Nullable String newBranch,
        @Nonnull ProgressIndicator indicator,
        @Nonnull Collection<GitRepository> openedInOtherWorktreeOut
    ) {
        AtomicBoolean result = new AtomicBoolean();
        GitPreservingProcess preservingProcess = new GitPreservingProcess(
            myProject,
            myGit,
            GitUtil.getRootsFromRepositories(repositories),
            "checkout",
            reference,
            GitVcsSettings.UpdateChangesPolicy.STASH,
            indicator,
            () -> result.set(checkoutOrNotify(repositories, reference, newBranch, false, openedInOtherWorktreeOut))
        );
        preservingProcess.execute();
        return result.get();
    }

    /**
     * Checks out or shows an error message.
     * Repositories for which the user chose to open the already existing worktree instead of checking out are added
     * to {@code openedInOtherWorktreeOut}; they count neither as successful nor as failed, so callers should exclude
     * them from post-success bookkeeping (marking successful, refreshing VFS, etc).
     */
    private boolean checkoutOrNotify(
        @Nonnull List<GitRepository> repositories,
        @Nonnull String reference,
        @Nullable String newBranch,
        boolean force,
        @Nonnull Collection<GitRepository> openedInOtherWorktreeOut
    ) {
        GitCompoundResult compoundResult = new GitCompoundResult(myProject);
        for (GitRepository repository : repositories) {
            GitBranchAlreadyCheckedOutInOtherWorktreeDetector otherWorktreeDetector = new GitBranchAlreadyCheckedOutInOtherWorktreeDetector();
            GitCommandResult result = myGit.checkout(repository, reference, newBranch, force, myDetach, myReset, false, otherWorktreeDetector);
            if (!result.success() && otherWorktreeDetector.isDetected()) {
                GitBranchUiHandler.CheckoutInOtherWorktreeDecision decision =
                    showCheckoutInOtherWorktreeDialog(repository, otherWorktreeDetector, reference);
                if (decision == GitBranchUiHandler.CheckoutInOtherWorktreeDecision.CHECKOUT_ANYWAY) {
                    result = myGit.checkout(repository, reference, newBranch, force, myDetach, myReset, true);
                }
                else if (decision == GitBranchUiHandler.CheckoutInOtherWorktreeDecision.OPEN_EXISTING_WORKTREE &&
                    openExistingWorktree(repository, otherWorktreeDetector.getMatch())) {
                    openedInOtherWorktreeOut.add(repository);
                    continue;
                }
            }
            compoundResult.append(repository, result);
        }
        if (compoundResult.totalSuccess()) {
            return true;
        }
        notifyError(LocalizeValue.localizeTODO("Couldn't checkout " + reference), compoundResult.getErrorOutputWithReposIndication());
        return false;
    }

    private void refresh(GitRepository... repositories) {
        for (GitRepository repository : repositories) {
            refreshRoot(repository);
            // repository state will be auto-updated with this VFS refresh => in general there is no need to call GitRepository#update()
            // but to avoid problems of the asynchronous refresh, let's force update the repository info.
            repository.update();
        }
    }

    private class RollbackOperationNotificationListener implements NotificationListener {
        @Override
        @RequiredUIAccess
        public void hyperlinkActivated(@Nonnull Notification notification, @Nonnull HyperlinkEvent event) {
            if (event.getDescription().equalsIgnoreCase("rollback")) {
                rollback();
            }
        }
    }
}
