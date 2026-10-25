// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.remote.hosting;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.progress.ProgressBuilderFactory;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.application.util.HtmlChunk;
import consulo.component.ProcessCanceledException;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.project.ui.notification.event.NotificationListener;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.dialog.DialogService;
import consulo.util.collection.ContainerUtil;
import consulo.util.concurrent.coroutine.Coroutine;
import consulo.util.concurrent.coroutine.step.CodeExecution;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.ChangeListManager;
import consulo.versionControlSystem.change.CommitNotification;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitLocalBranch;
import git4idea.GitUtil;
import git4idea.actions.GitInit;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import git4idea.remote.hosting.ui.ShareProjectExistingRemotesDialog;
import git4idea.remote.hosting.ui.ShareProjectUntrackedFilesDialog;
import git4idea.remote.hosting.ui.ShareProjectUntrackedFilesDialogResult;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.GitFileUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;

@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitShareProjectService {
    private static final Logger LOG = Logger.getInstance(GitShareProjectService.class);

    private static final int STEPS_COUNT = 7;

    private final Project myProject;
    private final ProgressBuilderFactory myProgressBuilderFactory;

    @Inject
    public GitShareProjectService(Project project, ProgressBuilderFactory progressBuilderFactory) {
        myProject = project;
        myProgressBuilderFactory = progressBuilderFactory;
    }

    public static GitShareProjectService getInstance(Project project) {
        return project.getInstance(GitShareProjectService.class);
    }

    /**
     * @return {@code true} only when the user has chosen to continue with sharing the project.
     */
    @RequiredUIAccess
    public CompletableFuture<Boolean> showExistingRemotesDialog(String hostServiceName,
                                                                @Nullable GitRepository gitRepository,
                                                                Set<? extends HostedGitRepositoryMapping> knownRepositories) {
        List<String> possibleRemotes = new ArrayList<>();
        if (gitRepository != null) {
            for (HostedGitRepositoryMapping mapping : knownRepositories) {
                if (mapping.getRemote().repository() == gitRepository) {
                    possibleRemotes.add(mapping.getRemote().url());
                }
            }
        }

        if (!possibleRemotes.isEmpty()) {
            ShareProjectExistingRemotesDialog existingRemotesDialog = new ShareProjectExistingRemotesDialog(hostServiceName, possibleRemotes);
            return myProject.getApplication().getInstance(DialogService.class)
                .build(existingRemotesDialog)
                .showAsync()
                .handle((value, error) -> error == null);
        }

        return CompletableFuture.completedFuture(true);
    }

    // get gitRepository
    // check for existing git repo
    // check available repos and privateRepo access (net)
    // Show dialog (window)
    // create GitHub/Lab repo (net)
    // create local git repo (if not exist)
    // add GitHub/Lab as a remote host
    // make first commit
    // push everything (net)
    public <RepoResult> CompletableFuture<?> performShareProject(String hostServiceName,
                                                                 @Nullable GitRepository gitRepository,
                                                                 VirtualFile root,
                                                                 String repositoryName,
                                                                 String remoteName,
                                                                 Supplier<CompletableFuture<RepoResult>> createRepo,
                                                                 Function<RepoResult, String> extractRepoWebUrl,
                                                                 Function<RepoResult, String> extractRepoRemoteUrl) {
        return myProgressBuilderFactory.newProgressBuilder(myProject, GitLocalize.shareProcess(hostServiceName))
            .cancelable()
            .execute(myProject.getUIAccess(), () -> Coroutine.first(CodeExecution.<Object, Object>apply((input, continuation) -> {
                try {
                    doShareProject(hostServiceName, gitRepository, root, repositoryName, remoteName, createRepo, extractRepoWebUrl,
                        extractRepoRemoteUrl);
                }
                catch (Throwable e) {
                    Throwable error = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                    if (error instanceof ProcessCanceledException) {
                        return null;
                    }
                    NotificationService.getInstance()
                        .newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                        .title(GitLocalize.shareErrorFailedToCreateRepo())
                        .content(getErrorTextFromException(error))
                        .notify(myProject);
                }
                return null;
            })));
    }

    private <RepoResult> void doShareProject(String hostServiceName,
                                             @Nullable GitRepository gitRepository,
                                             VirtualFile root,
                                             String repositoryName,
                                             String remoteName,
                                             Supplier<CompletableFuture<RepoResult>> createRepo,
                                             Function<RepoResult, String> extractRepoWebUrl,
                                             Function<RepoResult, String> extractRepoRemoteUrl) throws VcsException {
        // create GitHub/Lab repo (network)
        itemStep(0, GitLocalize.shareProcessCreatingRepository(hostServiceName));
        LOG.info("Creating " + hostServiceName + " repository");
        RepoResult repoResult = createRepo.get().join();
        String url = extractRepoWebUrl.apply(repoResult);
        LOG.info("Successfully created " + hostServiceName + " repository");

        // creating empty git repo if git is not initialized
        itemStep(1, GitLocalize.shareProcessCreatingGitRepository());
        GitRepository repository = ensureGitRepositoryExistsAndGet(hostServiceName, gitRepository, root);
        if (repository == null) {
            return;
        }

        // retrieve remote URL
        itemStep(2, GitLocalize.shareProcessRetrievingUsername());
        String remoteUrl = extractRepoRemoteUrl.apply(repoResult);

        // git remote add origin git@github.com:login/name.git
        itemStep(3, GitLocalize.shareProcessAddingGhAsRemoteHost(hostServiceName));
        LOG.info("Adding " + hostServiceName + " as a remote host");
        addGitRemote(repository, remoteName, remoteUrl);

        // create sample commit for binding project
        if (!performFirstCommitIfRequired(hostServiceName, root, repository, repositoryName, url)) {
            return;
        }

        // git push origin master
        GitLocalBranch currentBranch = repository.getCurrentBranch();
        itemStep(6, GitLocalize.shareProcessPushingToHostMaster(hostServiceName, currentBranch == null ? "" : currentBranch.getName()));
        LOG.info("Pushing to " + hostServiceName + " master");
        if (!pushCurrentBranch(hostServiceName, repository, remoteName, remoteUrl, repositoryName, url)) {
            return;
        }

        // Show final success notification
        NotificationService.getInstance()
            .newInfo(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(GitLocalize.shareProcessSuccessfullyShared(hostServiceName))
            .content(LocalizeValue.of(HtmlChunk.link(url, repositoryName).toString()))
            .hyperlinkListener(NotificationListener.URL_OPENING_LISTENER)
            .notify(myProject);

        // Remove project sharing notification
        NotificationService.getInstance().getNotificationsOfType(CommitNotification.class, myProject).forEach(CommitNotification::expire);
    }

    private static void itemStep(int index, LocalizeValue text) {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        if (indicator != null) {
            indicator.checkCanceled();
            indicator.setIndeterminate(false);
            indicator.setFraction((double) index / STEPS_COUNT);
            indicator.setText(text);
        }
    }

    private static LocalizeValue getErrorTextFromException(Throwable e) {
        if (e instanceof UnknownHostException) {
            return GitLocalize.shareErrorUnknownhost(StringUtil.notNullize(e.getMessage()));
        }
        String message = e.getMessage();
        return message == null ? GitLocalize.shareErrorUnknown() : LocalizeValue.of(message);
    }

    private static void addGitRemote(GitRepository repository, String remoteName, String remoteUrl) throws VcsException {
        Git.getInstance().addRemote(repository, remoteName, remoteUrl).throwOnError();
        repository.update();
    }

    private @Nullable GitRepository ensureGitRepositoryExistsAndGet(String hostServiceName,
                                                                    @Nullable GitRepository repository,
                                                                    VirtualFile root) {
        LOG.info("Binding local project with " + hostServiceName);
        if (repository == null) {
            LOG.info("No git detected, creating empty git repo");
            if (!createEmptyGitRepository(root)) {
                return null;
            }
        }

        GitRepositoryManager repositoryManager = GitUtil.getRepositoryManager(myProject);
        GitRepository result = repositoryManager.getRepositoryForRoot(root);
        if (result == null) {
            NotificationService.getInstance()
                .newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(GitLocalize.shareErrorFailedToCreateRepo())
                .content(GitLocalize.shareErrorCannotFindGitRepo())
                .notify(myProject);
            return null;
        }

        return result;
    }

    private boolean createEmptyGitRepository(VirtualFile root) {
        GitCommandResult result = Git.getInstance().init(myProject, root);
        if (!result.success()) {
            NotificationService.getInstance()
                .newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(GitLocalize.initializingTitle())
                .content(LocalizeValue.of(result.getErrorOutputAsHtmlString()))
                .notify(myProject);
            LOG.info("Failed to create empty git repo: " + result.getErrorOutputAsJoinedString());
            return false;
        }
        GitInit.refreshAndConfigureVcsMappings(myProject, root, root.getPath());
        GitUtil.generateGitignoreFileIfNeeded(myProject, root);
        return true;
    }

    private boolean performFirstCommitIfRequired(String hostServiceName,
                                                 VirtualFile root,
                                                 GitRepository repository,
                                                 String name,
                                                 String url) {
        // check if there is no commits
        if (!repository.isFresh()) {
            return true;
        }

        myProject.getApplication().saveAllWithProgress(myProject.getUIAccess()).join();

        LOG.info("Trying to commit");
        try {
            LOG.info("Adding files for commit");

            // ask for files to add
            itemStep(4, GitLocalize.shareProcessAddingFiles());
            GitRepositoryManager manager = GitUtil.getRepositoryManager(myProject);
            List<VirtualFile> trackedFiles = new ArrayList<>();
            for (VirtualFile file : ChangeListManager.getInstance(myProject).getAffectedFiles()) {
                if (file.isInLocalFileSystem() && manager.getRepositoryForFileQuick(file) == repository) {
                    trackedFiles.add(file);
                }
            }
            Collection<VirtualFile> untrackedFiles = filterOutIgnored(repository.getUntrackedFilesHolder().retrieveUntrackedFiles());
            trackedFiles.removeAll(untrackedFiles); // fix IDEA-119855

            List<VirtualFile> allFiles = new ArrayList<>();
            allFiles.addAll(trackedFiles);
            allFiles.addAll(untrackedFiles);

            Optional<ShareProjectUntrackedFilesDialogResult> data = CompletableFuture.supplyAsync(() -> {
                ShareProjectUntrackedFilesDialog dialog = new ShareProjectUntrackedFilesDialog(myProject, allFiles);
                if (!trackedFiles.isEmpty()) {
                    dialog.setSelectedFiles(trackedFiles);
                }
                return dialog.showAsync();
            }, myProject.getUIAccess()).thenCompose(future -> future).join();

            if (data.isEmpty() || data.get().selectedFiles().isEmpty()) {
                NotificationService.getInstance()
                    .newInfo(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                    .title(GitLocalize.shareProcessEmptyProjectCreated(hostServiceName))
                    .content(LocalizeValue.of(HtmlChunk.link(url, name).toString()))
                    .hyperlinkListener(NotificationListener.URL_OPENING_LISTENER)
                    .notify(myProject);
                return false;
            }
            List<VirtualFile> files2commit = data.get().selectedFiles();
            String commitMessage = data.get().commitMessage();

            Collection<VirtualFile> files2add = ContainerUtil.intersection(untrackedFiles, files2commit);
            Collection<VirtualFile> files2rm = ContainerUtil.subtract(trackedFiles, files2commit);
            Set<VirtualFile> modified = new HashSet<>(trackedFiles);
            modified.addAll(files2commit);

            GitFileUtils.addFiles(myProject, root, files2add);
            GitFileUtils.deleteFilesFromCache(myProject, root, files2rm);

            // commit
            itemStep(5, GitLocalize.shareProcessPerformingCommit());
            GitLineHandler handler = new GitLineHandler(myProject, root, GitCommand.COMMIT);
            handler.setStdoutSuppressed(false);
            handler.addParameters("-m", commitMessage);
            handler.endOptions();

            Git.getInstance().runCommand(handler).throwOnError();
            VcsFileUtil.markFilesDirty(myProject, modified);
        }
        catch (VcsException e) {
            LOG.warn(e);
            notifyProjectCreationFailure(hostServiceName, name, url,
                GitLocalize.shareErrorInitCommitFailed(hostServiceName).get() + getErrorTextFromException(e).get());
            return false;
        }

        LOG.info("Successfully created initial commit");
        return true;
    }

    private Collection<VirtualFile> filterOutIgnored(Collection<VirtualFile> files) {
        ChangeListManager changeListManager = ChangeListManager.getInstance(myProject);
        return ContainerUtil.filter(files, file -> !changeListManager.isIgnoredFile(file));
    }

    private boolean pushCurrentBranch(String hostServiceName,
                                      GitRepository repository,
                                      String remoteName,
                                      String remoteUrl,
                                      String name,
                                      String url) {
        GitLocalBranch currentBranch = repository.getCurrentBranch();
        if (currentBranch == null) {
            notifyProjectCreationFailure(hostServiceName, name, url, GitLocalize.shareErrorPushNoCurrentBranch(hostServiceName).get());
            return false;
        }
        GitCommandResult result = Git.getInstance().push(repository, remoteName, remoteUrl, null, currentBranch.getName(), true);
        if (!result.success()) {
            notifyProjectCreationFailure(hostServiceName, name, url,
                GitLocalize.shareErrorPushFailed(hostServiceName, result.getErrorOutputAsHtmlString()).get());
            return false;
        }
        return true;
    }

    private void notifyProjectCreationFailure(String hostServiceName, String name, String url, String postfix) {
        NotificationService.getInstance()
            .newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(GitLocalize.shareErrorCannotFinish(hostServiceName))
            .content(LocalizeValue.of(GitLocalize.shareErrorCreatedProject().get() + HtmlChunk.link(url, " '" + name + "' ") + postfix))
            .hyperlinkListener(NotificationListener.URL_OPENING_LISTENER)
            .notify(myProject);
    }
}
