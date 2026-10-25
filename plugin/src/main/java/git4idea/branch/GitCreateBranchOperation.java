/*
 * Copyright 2000-2017 JetBrains s.r.o.
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

import consulo.git.localize.GitBranchesLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.versionControlSystem.VcsNotifier;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitCompoundResult;
import git4idea.repo.GitRepository;

import java.util.Collection;
import java.util.Map;

import static git4idea.util.GitUIUtil.code;

class GitCreateBranchOperation extends GitBranchOperation {
    private final String myBranchName;
    private final Map<GitRepository, String> myStartPoints;
    private final boolean myForce;

    GitCreateBranchOperation(Project project,
                             Git git,
                             GitBranchUiHandler uiHandler,
                             String branchName,
                             Map<GitRepository, String> startPoints,
                             boolean force) {
        super(project, git, uiHandler, startPoints.keySet());
        myBranchName = branchName;
        myStartPoints = startPoints;
        myForce = force;
    }

    @Override
    public void execute() {
        boolean fatalErrorHappened = false;
        while (hasMoreRepositories() && !fatalErrorHappened) {
            GitRepository repository = next();
            GitCommandResult result = myGit.branchCreate(repository, myBranchName, myStartPoints.get(repository), myForce);

            if (result.success()) {
                repository.update();
                markSuccessful(repository);
            }
            else {
                fatalError(GitBranchesLocalize.createBranchOperationCouldNotCreateNewBranch(myBranchName), result.getErrorOutputAsJoinedValue());
                fatalErrorHappened = true;
            }
        }

        if (!fatalErrorHappened) {
            notifySuccess();
            updateRecentBranch();
        }
    }

    @Override
    protected void rollback() {
        Collection<GitRepository> repositories = getSuccessfulRepositories();
        GitCompoundResult deleteResult = new GitCompoundResult(myProject);
        for (GitRepository repository : repositories) {
            deleteResult.append(repository, myGit.branchDelete(repository, myBranchName, false));
            repository.update();
        }

        if (deleteResult.totalSuccess()) {
            myNotificationService.newInfo(VcsNotifier.NOTIFICATION_GROUP_ID)
                .title(GitBranchesLocalize.createBranchOperationRollbackSuccessful())
                .content(GitBranchesLocalize.createBranchOperationDeletedBranch(myBranchName))
                .notify(myProject);
        }
        else {
            myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(GitBranchesLocalize.createBranchOperationErrorDuringRollback())
                .content(deleteResult.getErrorOutputWithReposIndication())
                .notify(myProject);
        }
    }

    @Override
    public LocalizeValue getSuccessMessage() {
        return GitBranchesLocalize.createBranchOperationBranchCreated(code(myBranchName));
    }

    @Override
    protected LocalizeValue getRollbackProposal() {
        return LocalizeValue.join(
            GitBranchesLocalize.createBranchOperationHoweverTheBranchWasCreatedInTheFollowingRepositories(getSuccessfulRepositories().size()),
            LocalizeValue.of("<br/>" + successfulRepositoriesJoined() + "<br/>"),
            GitBranchesLocalize.createBranchOperationYouMayRollbackNotToLetBranchesDiverge(myBranchName)
        );
    }

    @Override
    protected LocalizeValue getOperationName() {
        return GitBranchesLocalize.createBranchOperationName();
    }
}
