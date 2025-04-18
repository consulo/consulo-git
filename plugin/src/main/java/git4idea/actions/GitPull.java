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

import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.git.localize.GitLocalize;
import consulo.localHistory.Label;
import consulo.localHistory.LocalHistory;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.update.ActionInfo;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.GitLineHandler;
import git4idea.commands.GitStandardProgressAnalyzer;
import git4idea.commands.GitTask;
import git4idea.commands.GitTaskResultHandlerAdapter;
import git4idea.merge.GitMergeUtil;
import git4idea.merge.GitPullDialog;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.GitUIUtil;
import jakarta.annotation.Nonnull;

import java.util.List;
import java.util.Set;

/**
 * Git "pull" action
 */
public class GitPull extends GitRepositoryAction {
    @Override
    @Nonnull
    protected LocalizeValue getActionName() {
        return GitLocalize.pullActionName();
    }

    @Override
    @RequiredUIAccess
    protected void perform(
        @Nonnull final Project project,
        @Nonnull final List<VirtualFile> gitRoots,
        @Nonnull final VirtualFile defaultRoot,
        final Set<VirtualFile> affectedRoots,
        final List<VcsException> exceptions
    ) throws VcsException {
        final GitPullDialog dialog = new GitPullDialog(project, gitRoots, defaultRoot);
        dialog.show();
        if (!dialog.isOK()) {
            return;
        }
        final Label beforeLabel = LocalHistory.getInstance().putSystemLabel(project, "Before update");

        new Task.Backgroundable(project, GitLocalize.pullingTitle(dialog.getRemote()), true) {
            @Override
            @RequiredUIAccess
            public void run(@Nonnull ProgressIndicator indicator) {
                final GitRepositoryManager repositoryManager = GitUtil.getRepositoryManager((Project)myProject);

                GitRepository repository = repositoryManager.getRepositoryForRoot(dialog.gitRoot());
                assert repository != null : "Repository can't be null for root " + dialog.gitRoot();
                String remoteOrUrl = dialog.getRemote();


                GitRemote remote = GitUtil.findRemoteByName(repository, remoteOrUrl);
                String url = (remote == null) ? remoteOrUrl : remote.getFirstUrl();
                if (url == null) {
                    return;
                }

                final GitLineHandler handler = dialog.makeHandler(url, remote == null ? null : remote.getPuttyKeyFile());

                final VirtualFile root = dialog.gitRoot();
                affectedRoots.add(root);
                String revision = repository.getCurrentRevision();
                if (revision == null) {
                    return;
                }
                final GitRevisionNumber currentRev = new GitRevisionNumber(revision);

                GitTask pullTask = new GitTask(project, handler, GitLocalize.pullingTitle(dialog.getRemote()));
                pullTask.setProgressIndicator(indicator);
                pullTask.setProgressAnalyzer(new GitStandardProgressAnalyzer());
                pullTask.execute(true, false, new GitTaskResultHandlerAdapter() {
                    @Override
                    protected void onSuccess() {
                        root.refresh(false, true);
                        GitMergeUtil.showUpdates(
                            GitPull.this,
                            project,
                            exceptions,
                            root,
                            currentRev,
                            beforeLabel,
                            getActionName(),
                            ActionInfo.UPDATE
                        );
                        repositoryManager.updateRepository(root);
                        runFinalTasks(project, GitVcs.getInstance(project), affectedRoots, getActionName(), exceptions);
                    }

                    @Override
                    protected void onFailure() {
                        GitUIUtil.notifyGitErrors(project, "Error pulling " + dialog.getRemote(), "", handler.errors());
                        repositoryManager.updateRepository(root);
                    }
                });
            }
        }.queue();
    }

    @Override
    protected boolean executeFinalTasksSynchronously() {
        return false;
    }
}
