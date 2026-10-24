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
package git4idea.fetch;

import consulo.annotation.component.ServiceImpl;
import consulo.application.util.HtmlBuilder;
import consulo.application.util.HtmlChunk;
import consulo.application.progress.EmptyProgressIndicator;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.application.util.registry.Registry;
import consulo.component.ProcessCanceledException;
import consulo.externalProcessAuthHelper.AuthenticationGate;
import consulo.externalProcessAuthHelper.RestrictingAuthenticationGate;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.process.ProcessOutputType;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.util.dataholder.Key;
import consulo.util.lang.Pair;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.distributed.MultiMessage;
import consulo.versionControlSystem.distributed.MultiRootMessage;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;
import git4idea.GitUtil;
import git4idea.commands.Git;
import git4idea.commands.GitAuthenticationListener;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitImpl;
import git4idea.commands.GitLineHandlerAdapter;
import git4idea.commands.GitLineHandlerListener;
import git4idea.config.GitConfigUtil;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
@ServiceImpl
public class GitFetchSupportImpl implements GitFetchSupport {
    private static final Logger LOG = Logger.getInstance(GitFetchSupportImpl.class);
    private static final Pattern PRUNE_PATTERN = Pattern.compile("\\s*x\\s*\\[deleted\\].*->\\s*(\\S*)"); // x [deleted]  (none) -> origin/branch
    private static final int MAX_SSH_CONNECTIONS = 10; // by default SSH server has a limit of 10 multiplexed ssh connection

    private final Project myProject;
    private final NotificationService myNotificationService;

    private final GitRemoteOperationQueueImpl myFetchQueue = new GitRemoteOperationQueueImpl();
    private final AtomicInteger myFetchRequestCounter = new AtomicInteger();

    @Inject
    public GitFetchSupportImpl(Project project, NotificationService notificationService) {
        myProject = project;
        myNotificationService = notificationService;
    }

    private static GitImpl git() {
        return (GitImpl) Git.getInstance();
    }

    @Override
    public @Nullable GitRemote getDefaultRemoteToFetch(GitRepository repository) {
        Collection<GitRemote> remotes = repository.getRemotes();
        if (remotes.isEmpty()) {
            return null;
        }
        if (remotes.size() == 1) {
            return remotes.iterator().next();
        }
        // this emulates behavior of the native `git fetch`:
        // if current branch doesn't give a hint, then return "origin"; if there is no "origin", don't guess and fail
        GitLocalBranch currentBranch = repository.getCurrentBranch();
        GitRemoteBranch trackedBranch = currentBranch == null ? null : currentBranch.findTrackedBranch(repository);
        if (trackedBranch != null) {
            return trackedBranch.getRemote();
        }
        return GitUtil.findRemoteByName(repository, GitRemote.ORIGIN_NAME);
    }

    @Override
    public GitFetchResult fetchDefaultRemote(Collection<GitRepository> repositories) {
        List<GitFetchSpec> remotesToFetch = new ArrayList<>();
        for (GitRepository repository : repositories) {
            GitRemote remote = getDefaultRemoteToFetch(repository);
            if (remote != null) {
                remotesToFetch.add(new GitFetchSpec(repository, remote));
            }
            else {
                LOG.info("No remote to fetch found in " + repository);
            }
        }
        return fetch(remotesToFetch);
    }

    @Override
    public GitFetchResult fetchAllRemotes(Collection<GitRepository> repositories) {
        List<GitFetchSpec> remotesToFetch = new ArrayList<>();
        for (GitRepository repository : repositories) {
            if (repository.getRemotes().isEmpty()) {
                LOG.info("No remote to fetch found in " + repository);
            }
            else {
                for (GitRemote remote : repository.getRemotes()) {
                    remotesToFetch.add(new GitFetchSpec(repository, remote));
                }
            }
        }
        return fetch(remotesToFetch);
    }

    @Override
    public GitFetchResult fetch(GitRepository repository, GitRemote remote) {
        return fetch(List.of(new GitFetchSpec(repository, remote)));
    }

    @Override
    public GitFetchResult fetchUnshallow(GitRepository repository, GitRemote remote) {
        return fetch(List.of(new GitFetchSpec(repository, remote, null, true, null)));
    }

    @Override
    public GitFetchResult fetch(GitRepository repository, GitRemote remote, String refspec) {
        return fetch(List.of(new GitFetchSpec(repository, remote, refspec)));
    }

    @Override
    public GitFetchResult fetchRemotes(Collection<Pair<GitRepository, GitRemote>> remotes) {
        List<GitFetchSpec> specs = new ArrayList<>();
        for (Pair<GitRepository, GitRemote> remote : remotes) {
            specs.add(new GitFetchSpec(remote.getFirst(), remote.getSecond()));
        }
        return fetch(specs);
    }

    @Override
    public GitFetchResult fetch(Collection<GitFetchSpec> fetchSpec) {
        try {
            int counterAfterIncrement = myFetchRequestCounter.incrementAndGet();
            if (counterAfterIncrement == 1) {
                myProject.getMessageBus().syncPublisher(GitFetchInProgressListener.class).fetchStarted();
            }

            return withIndicator(() -> {
                List<FetchTask> tasks = fetchInParallel(fetchSpec);
                List<SingleRemoteResult> results = waitForFetchTasks(tasks);

                Map<GitRepository, RepoResult> mergedResults = new LinkedHashMap<>();
                List<SingleRemoteResult> succeedResults = new ArrayList<>();
                for (SingleRemoteResult result : results) {
                    RepoResult res = mergedResults.get(result.fetchSpec().repository());
                    mergedResults.put(result.fetchSpec().repository(), mergeRepoResults(res, result));
                    if (result.success()) {
                        succeedResults.add(result);
                    }
                }
                Map<GitRepository, List<GitRemote>> successFetchesMap = new LinkedHashMap<>();
                for (SingleRemoteResult result : succeedResults) {
                    successFetchesMap.computeIfAbsent(result.fetchSpec().repository(), r -> new ArrayList<>()).add(result.fetchSpec().remote());
                }
                if (!successFetchesMap.isEmpty()) {
                    ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
                    GitFetchHandler.afterSuccessfulFetch(myProject, successFetchesMap, indicator != null ? indicator : new EmptyProgressIndicator());
                }
                return new FetchResultImpl(myProject, myNotificationService, mergedResults);
            });
        }
        finally {
            int counterAfterDecrement = myFetchRequestCounter.decrementAndGet();
            if (counterAfterDecrement == 0) {
                myProject.getMessageBus().syncPublisher(GitFetchInProgressListener.class).fetchFinished();
            }
        }
    }

    private static RepoResult mergeRepoResults(@Nullable RepoResult firstResult, SingleRemoteResult secondResult) {
        Map<GitRemote, SingleRemoteResult> results = new LinkedHashMap<>();
        if (firstResult != null) {
            results.putAll(firstResult.results());
        }
        results.put(secondResult.fetchSpec().remote(), secondResult);
        return new RepoResult(results);
    }

    @Override
    public boolean isFetchRunning() {
        return myFetchRequestCounter.get() > 0;
    }

    private List<FetchTask> fetchInParallel(Collection<GitFetchSpec> remotes) {
        List<FetchTask> tasks = new ArrayList<>();
        Set<GitRepository> repositories = new HashSet<>();
        for (GitFetchSpec remote : remotes) {
            repositories.add(remote.repository());
        }
        int maxThreads = getMaxThreads(repositories, remotes.size());
        LOG.debug("Fetching " + remotes + " using " + maxThreads + " threads");
        ExecutorService executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("GitFetch pool", maxThreads);
        ProgressIndicator currentIndicator = ProgressManager.getInstance().getProgressIndicator();
        ProgressIndicator commonIndicator = currentIndicator != null ? currentIndicator : new EmptyProgressIndicator();
        AuthenticationGate authenticationGate = new RestrictingAuthenticationGate();
        for (GitFetchSpec fetchTarget : remotes) {
            GitRepository repository = fetchTarget.repository();
            GitRemote remote = fetchTarget.remote();

            LOG.debug("Fetching " + remote + " in " + repository);
            Future<SingleRemoteResult> future = executor.submit(() -> {
                commonIndicator.checkCanceled();
                return ProgressManager.getInstance().runProcess(() -> {
                    commonIndicator.checkCanceled();
                    return myFetchQueue.executeForRemote(repository, remote, () -> doFetch(fetchTarget, authenticationGate));
                }, commonIndicator);
            });
            tasks.add(new FetchTask(fetchTarget, future));
        }
        return tasks;
    }

    private int getMaxThreads(Collection<GitRepository> repositories, int numberOfRemotes) {
        int config = Registry.intValue("git.parallel.fetch.threads", -3);
        int maxThreads;
        if (config > 0) {
            maxThreads = config;
        }
        else if (config == -1) {
            maxThreads = Runtime.getRuntime().availableProcessors();
        }
        else if (config == -2) {
            maxThreads = numberOfRemotes;
        }
        else if (config == -3) {
            maxThreads = Math.min(numberOfRemotes, Runtime.getRuntime().availableProcessors() * 2);
        }
        else {
            maxThreads = 1;
        }

        try {
            for (GitRepository repository : repositories) {
                String helper = GitConfigUtil.getValue(myProject, repository.getRoot(), "credential.helper");
                if ("store".equalsIgnoreCase(helper)) {
                    return 1;
                }
            }
        }
        catch (VcsException e) {
            LOG.warn(e);
            return 1;
        }

        return Math.max(1, Math.min(maxThreads, MAX_SSH_CONNECTIONS));
    }

    private static List<SingleRemoteResult> waitForFetchTasks(List<FetchTask> tasks) {
        List<SingleRemoteResult> results = new ArrayList<>();
        for (FetchTask task : tasks) {
            try {
                results.add(task.future().get());
            }
            catch (CancellationException | InterruptedException e) {
                throw new ProcessCanceledException(e);
            }
            catch (ExecutionException e) {
                if (e.getCause() instanceof ProcessCanceledException pce) {
                    throw pce;
                }
                String message = e.getCause() != null ? e.getCause().getMessage() : null;
                results.add(new SingleRemoteResult(task.fetchSpec(), message != null ? message : GitLocalize.errorDialogTitle().get(), List.of()));
                LOG.error(e);
            }
        }
        return results;
    }

    private static <T> T withIndicator(Supplier<T> operation) {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        LocalizeValue prevText = indicator != null ? indicator.getText() : LocalizeValue.empty();
        if (indicator != null) {
            indicator.setText(GitLocalize.gitFetchProgress());
        }
        try {
            return operation.get();
        }
        finally {
            if (indicator != null) {
                indicator.setText(prevText);
                indicator.setText2(LocalizeValue.empty());
            }
        }
    }

    private SingleRemoteResult doFetch(GitFetchSpec fetchTarget, @Nullable AuthenticationGate authenticationGate) {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        GitLineHandlerListener progressListener = new GitLineHandlerAdapter() {
            @Override
            public void onLineAvailable(String line, Key outputType) {
                if (indicator != null && ProcessOutputType.isStderr(outputType)) {
                    indicator.setText2(LocalizeValue.of(line));
                }
            }
        };

        String[] params = fetchTarget.asParams();
        GitRepository repository = fetchTarget.repository();
        GitRemote remote = fetchTarget.remote();
        GitCommandResult result = git().fetch(repository, remote, List.of(progressListener), authenticationGate, fetchTarget.authMode(), params);
        List<String> pruned = new ArrayList<>();
        for (String line : result.getOutput()) {
            String ref = getPrunedRef(line);
            if (ref != null) {
                pruned.add(ref);
            }
        }
        if (result.success()) {
            myProject.getMessageBus().syncPublisher(GitAuthenticationListener.class).authenticationSucceeded(repository, remote);
            repository.update();
        }
        String error = result.success() ? null : result.getErrorOutputAsJoinedString();
        return new SingleRemoteResult(fetchTarget, error, pruned);
    }

    private static @Nullable String getPrunedRef(String line) {
        Matcher matcher = PRUNE_PATTERN.matcher(line);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private record FetchTask(GitFetchSpec fetchSpec, Future<SingleRemoteResult> future) {
    }

    private record RepoResult(Map<GitRemote, SingleRemoteResult> results) {
        boolean totallySuccessful() {
            for (SingleRemoteResult result : results.values()) {
                if (!result.success()) {
                    return false;
                }
            }
            return true;
        }

        String error() {
            MultiMessage<GitRemote> errorMessage = multiRemoteMessage(true);
            for (Map.Entry<GitRemote, SingleRemoteResult> entry : results.entrySet()) {
                String error = entry.getValue().error();
                if (error != null) {
                    errorMessage.append(entry.getKey(), error);
                }
            }
            return errorMessage.asString();
        }

        String prunedRefs() {
            MultiMessage<GitRemote> prunedRefs = multiRemoteMessage(false);
            for (Map.Entry<GitRemote, SingleRemoteResult> entry : results.entrySet()) {
                List<String> refs = entry.getValue().prunedRefs();
                if (!refs.isEmpty()) {
                    prunedRefs.append(entry.getKey(), String.join("\n", refs));
                }
            }
            return prunedRefs.asString();
        }

        /*
           For simplicity, remote and repository results are merged separately.
           It means that they are not merged, if two repositories have two remotes,
           and then fetch succeeds for the first remote in both repos, and fails for the second remote in both repos.
           Such cases are rare, and can be handled when actual problem is reported.
         */
        private MultiMessage<GitRemote> multiRemoteMessage(boolean remoteInPrefix) {
            return new MultiMessage<>(results.keySet(), GitRemote::getName, GitRemote::getName, remoteInPrefix);
        }
    }

    private record SingleRemoteResult(GitFetchSpec fetchSpec, @Nullable String error, List<String> prunedRefs) {
        boolean success() {
            return error == null;
        }
    }

    private static class FetchResultImpl implements GitFetchResult {
        private final Project myProject;
        private final NotificationService myNotificationService;
        private final Map<GitRepository, RepoResult> myResults;
        private final boolean myIsFailed;

        FetchResultImpl(Project project, NotificationService notificationService, Map<GitRepository, RepoResult> results) {
            myProject = project;
            myNotificationService = notificationService;
            myResults = results;
            boolean failed = false;
            for (RepoResult result : results.values()) {
                if (!result.totallySuccessful()) {
                    failed = true;
                    break;
                }
            }
            myIsFailed = failed;
        }

        @Override
        public boolean isSuccessful() {
            return !myIsFailed;
        }

        @Override
        public void showNotification() {
            doShowNotification(null);
        }

        @Override
        public boolean showNotificationIfFailed() {
            if (myIsFailed) {
                doShowNotification(null);
            }
            return !myIsFailed;
        }

        @Override
        public boolean showNotificationIfFailed(LocalizeValue title) {
            if (myIsFailed) {
                doShowNotification(title);
            }
            return !myIsFailed;
        }

        @Override
        public void throwExceptionIfFailed() throws VcsException {
            if (myIsFailed) {
                throw new VcsException(buildMessage(null));
            }
        }

        private void doShowNotification(@Nullable LocalizeValue failureTitle) {
            if (myIsFailed) {
                showFailureNotification(failureTitle);
            }
            else {
                showSuccessNotification();
            }
        }

        private void showSuccessNotification() {
            myNotificationService.newInfo(VcsNotifier.STANDARD_NOTIFICATION)
                .title(GitLocalize.notificationTitleFetchSuccess())
                .content(LocalizeValue.of(buildMessage(null)))
                .notify(myProject);
        }

        private void showFailureNotification(@Nullable LocalizeValue failureTitle) {
            myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(failureTitle != null ? failureTitle : GitLocalize.notificationTitleFetchFailure())
                .content(LocalizeValue.of(buildMessage(failureTitle)))
                .notify(myProject);
        }

        private String buildMessage(@Nullable LocalizeValue failureTitle) {
            List<VirtualFile> roots = new ArrayList<>();
            for (GitRepository repository : myResults.keySet()) {
                roots.add(repository.getRoot());
            }
            MultiRootMessage errorMessage = new MultiRootMessage(myProject, roots, true);
            MultiRootMessage prunedRefs = new MultiRootMessage(myProject, roots);

            Map<GitRepository, RepoResult> failed = new LinkedHashMap<>();
            for (Map.Entry<GitRepository, RepoResult> entry : myResults.entrySet()) {
                if (!entry.getValue().totallySuccessful()) {
                    failed.put(entry.getKey(), entry.getValue());
                }
            }

            for (Map.Entry<GitRepository, RepoResult> entry : failed.entrySet()) {
                errorMessage.append(entry.getKey().getRoot(), entry.getValue().error());
            }
            for (Map.Entry<GitRepository, RepoResult> entry : myResults.entrySet()) {
                prunedRefs.append(entry.getKey().getRoot(), entry.getValue().prunedRefs());
            }

            HtmlBuilder sb = new HtmlBuilder();
            if (myIsFailed && failed.size() != roots.size() && !failed.isEmpty()) {
                LocalizeValue title = failureTitle != null ? failureTitle : GitLocalize.notificationTitleFetchFailure();
                sb.append(HtmlChunk.text(title.get()).bold());
                sb.append(GitUtil.mention(failed.keySet()));
            }
            appendDetails(sb, errorMessage);
            appendDetails(sb, prunedRefs);
            return sb.toString();
        }

        private static void appendDetails(HtmlBuilder sb, MultiRootMessage details) {
            String text = details.asString();
            if (!text.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.br();
                }
                sb.appendRaw(text);
            }
        }
    }
}
