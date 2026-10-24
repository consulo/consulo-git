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
package git4idea.branch;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.application.util.registry.Registry;
import consulo.component.messagebus.MessageBusConnection;
import consulo.disposer.Disposable;
import consulo.externalProcessAuthHelper.AuthenticationMode;
import consulo.logging.Logger;
import consulo.platform.Platform;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.util.collection.MultiMap;
import consulo.util.lang.Pair;
import consulo.util.lang.StringUtil;
import consulo.util.lang.lazy.LazyValue;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.distributed.branch.DvcsInOutCountersInProject;
import consulo.versionControlSystem.distributed.branch.DvcsInOutCountersInRepo;
import consulo.versionControlSystem.distributed.branch.DvcsInOutProjectState;
import consulo.versionControlSystem.distributed.branch.DvcsIncomingOutgoingListener;
import consulo.versionControlSystem.log.Hash;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.versionControlSystem.util.VcsUtil;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;
import git4idea.commands.Git;
import git4idea.commands.GitAuthenticationListener;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import git4idea.config.GitIncomingRemoteCheckStrategy;
import git4idea.config.GitVcsSettings;
import git4idea.config.GitVcsSettingsListener;
import git4idea.config.GitVersionSpecialty;
import git4idea.fetch.GitFetchSpec;
import git4idea.fetch.GitFetchSupport;
import git4idea.history.GitHistoryUtils;
import git4idea.push.GitPushSupport;
import git4idea.push.GitPushTarget;
import git4idea.remote.GitRemoteUrlUtil;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRefUtil;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryChangeListener;
import git4idea.repo.GitRepositoryManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static consulo.externalProcessAuthHelper.AuthenticationMode.NONE;
import static consulo.externalProcessAuthHelper.AuthenticationMode.SILENT;
import static git4idea.repo.GitRefUtil.addRefsHeadsPrefixIfNeeded;
import static git4idea.repo.GitRefUtil.getResolvedHashes;

@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public final class GitBranchIncomingOutgoingManager implements GitRepositoryChangeListener, GitAuthenticationListener, Disposable {
    private static final Logger LOG = Logger.getInstance(GitBranchIncomingOutgoingManager.class);

    private static final String MAC_DEFAULT_LAUNCH = "com.apple.launchd"; //NON-NLS

    private static final LazyValue<Boolean> HAS_EXTERNAL_SSH_AGENT = LazyValue.notNull(GitBranchIncomingOutgoingManager::hasExternalSSHAgent);

    private static final int UPDATE_DELAY_MS = 1000;

    private final Object LOCK = new Object();
    private final Set<GitRepository> myDirtyReposWithIncoming = new HashSet<>();
    private final Set<GitRepository> myDirtyReposWithOutgoing = new HashSet<>();
    private boolean myShouldRequestRemoteInfo;

    private final ScheduledExecutorService myQueue =
        AppExecutorUtil.createBoundedScheduledExecutorService("GitBranchIncomingOutgoingManager", 1);
    private @Nullable ScheduledFuture<?> myQueuedUpdate;

    //store map from local branch to related cached remote branch hash per repository
    private final Map<GitRepository, Map<GitLocalBranch, Integer>> myLocalBranchesWithIncoming = new ConcurrentHashMap<>();
    private final Map<GitRepository, Map<GitLocalBranch, Hash>> myLocalBranchesToFetch = new ConcurrentHashMap<>();
    private final Map<GitRepository, Map<GitLocalBranch, Integer>> myLocalBranchesWithOutgoing = new ConcurrentHashMap<>();
    private final Project myProject;
    private @Nullable ScheduledFuture<?> myPeriodicalUpdater;
    private @Nullable MessageBusConnection myConnection;
    private final MultiMap<GitRepository, GitRemote> myAuthSuccessMap = MultiMap.createConcurrentSet();
    final AtomicReference<@Nullable Instant> myLastFetchTime = new AtomicReference<>(null);

    @Inject
    GitBranchIncomingOutgoingManager(Project project) {
        myProject = project;
    }

    @Override
    public void dispose() {
        stopScheduling();
        synchronized (LOCK) {
            if (myQueuedUpdate != null) {
                myQueuedUpdate.cancel(false);
                myQueuedUpdate = null;
            }
        }
    }

    private static boolean hasExternalSSHAgent() {
        String sshAuthSock = Platform.current().os().getEnvironmentVariable("SSH_AUTH_SOCK");
        if (sshAuthSock == null) {
            return false;
        }

        if (StringUtil.contains(sshAuthSock, MAC_DEFAULT_LAUNCH)) {
            try {
                Path agentPath = Path.of(sshAuthSock);
                String originPath = agentPath.toString();
                String realPath = agentPath.toRealPath().toString();
                if (!originPath.equals(realPath)) {
                    return true;
                }
            }
            catch (Throwable ignored) {
            }

            return false;
        }

        return true;
    }

    public boolean hasIncomingFor(@Nullable GitRepository repository, String localBranchName) {
        return getBranchesWithIncoming(repository).contains(new GitLocalBranch(localBranchName));
    }

    public boolean hasOutgoingFor(@Nullable GitRepository repository, String localBranchName) {
        return getBranchesWithOutgoing(repository).contains(new GitLocalBranch(localBranchName));
    }

    /**
     * @return the number of incoming commits for the specified branch if present.
     * If there are no incoming commits, but it is known that something is non-fetched from a remote,
     */
    private @Nullable Integer getIncomingFor(@Nullable GitRepository repository, GitLocalBranch localBranch) {
        if (!shouldShow()) {
            return null;
        }
        Map<GitLocalBranch, Integer> incomingForRepo = repository == null ? null : myLocalBranchesWithIncoming.get(repository);
        if (incomingForRepo == null) {
            return null;
        }

        return incomingForRepo.get(localBranch);
    }

    private @Nullable Integer getOutgoingFor(@Nullable GitRepository repository, GitLocalBranch localBranch) {
        if (!shouldShow()) {
            return null;
        }
        Map<GitLocalBranch, Integer> outgoingForRepo = repository == null ? null : myLocalBranchesWithOutgoing.get(repository);
        if (outgoingForRepo == null) {
            return null;
        }
        return outgoingForRepo.get(localBranch);
    }

    public @Nullable DvcsInOutCountersInRepo getIncomingOutgoingCounters(GitRepository repository, GitLocalBranch localBranch) {
        if (!shouldShow()) {
            return null;
        }
        Integer incoming = getIncomingFor(repository, localBranch);
        Integer outgoing = getOutgoingFor(repository, localBranch);
        if (incoming == null && outgoing == null) {
            return null;
        }
        return new DvcsInOutCountersInRepo(incoming, outgoing);
    }

    public DvcsInOutCountersInProject getIncomingOutgoingState(GitRepository repository, GitLocalBranch localBranch) {
        return getIncomingOutgoingState(Collections.singleton(repository), localBranch);
    }

    public DvcsInOutCountersInProject getIncomingOutgoingState(Collection<GitRepository> repositories, GitLocalBranch localBranch) {
        if (!shouldShow() || repositories.isEmpty()) {
            return DvcsInOutCountersInProject.EMPTY;
        }

        Map<FilePath, DvcsInOutCountersInRepo> repoStates = new HashMap<>();
        for (GitRepository r : repositories) {
            DvcsInOutCountersInRepo counters = getIncomingOutgoingCounters(r, localBranch);
            if (counters != null) {
                repoStates.put(VcsUtil.getFilePath(r.getRoot()), counters);
            }
        }

        if (repoStates.isEmpty()) {
            return DvcsInOutCountersInProject.EMPTY;
        }

        return new DvcsInOutCountersInProject(repoStates);
    }

    private GitIncomingRemoteCheckStrategy getIncomingRemoteCheckStrategy() {
        return !shouldShow() ? GitIncomingRemoteCheckStrategy.NONE : GitVcsSettings.getInstance(myProject).getIncomingCommitsCheckStrategy();
    }

    private static boolean shouldShow() {
        return Registry.is("git.update.incoming.outgoing.info", true);
    }

    public static GitBranchIncomingOutgoingManager getInstance(Project project) {
        return project.getInstance(GitBranchIncomingOutgoingManager.class);
    }

    public @Nullable Instant getLastFetchTime() {
        return myLastFetchTime.get();
    }

    public boolean supportsIncomingOutgoing() {
        return GitVersionSpecialty.INCOMING_OUTGOING_BRANCH_INFO.existsIn(myProject);
    }

    public void activate() {
        myProject.getUIAccess().give(() -> {
            if (myProject.isDisposed()) {
                return;
            }
            if (myConnection == null) {
                myConnection = myProject.getMessageBus().connect(this);
                myConnection.subscribe(GitRepositoryChangeListener.class, this);
                myConnection.subscribe(GitAuthenticationListener.class, this);
                myConnection.subscribe(GitVcsSettingsListener.class, new GitVcsSettingsListener() {
                    @Override
                    public void incomingCommitsCheckStrategyChanged(GitIncomingRemoteCheckStrategy strategy) {
                        myProject.getUIAccess().give(() -> updateIncomingScheduling());
                    }
                });
            }
            updateAllBranchesWithOutgoing();
            updateIncomingScheduling();
        });
    }

    private void updateIncomingScheduling() {
        boolean shouldCheckIncomingOnRemote = getIncomingRemoteCheckStrategy() != GitIncomingRemoteCheckStrategy.NONE;
        if (myPeriodicalUpdater == null && shouldCheckIncomingOnRemote) {
            updateAllBranchesWithIncomingFromRemote();
            int timeout = Registry.intValue("git.update.incoming.info.time", 20);
            myPeriodicalUpdater = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(() -> updateAllBranchesWithIncomingFromRemote(), timeout, timeout, TimeUnit.MINUTES);
        }
        else if (myPeriodicalUpdater != null && !shouldCheckIncomingOnRemote) {
            stopScheduling();
        }
    }

    private void stopScheduling() {
        if (myPeriodicalUpdater != null) {
            myPeriodicalUpdater.cancel(true);
            myPeriodicalUpdater = null;
        }
    }

    private void scheduleUpdate() {
        synchronized (LOCK) {
            if (myProject.isDisposed()) {
                return;
            }
            if (myQueuedUpdate != null) {
                myQueuedUpdate.cancel(false);
            }
            myQueuedUpdate = myQueue.schedule(this::runUpdate, UPDATE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void runUpdate() {
        List<GitRepository> withIncoming;
        List<GitRepository> withOutgoing;

        boolean shouldRequestRemoteInfo;
        synchronized (LOCK) {
            withIncoming = new ArrayList<>(myDirtyReposWithIncoming);
            withOutgoing = new ArrayList<>(myDirtyReposWithOutgoing);
            shouldRequestRemoteInfo = myShouldRequestRemoteInfo;

            myDirtyReposWithIncoming.clear();
            myDirtyReposWithOutgoing.clear();
            myShouldRequestRemoteInfo = false;
        }

        for (GitRepository r : withOutgoing) {
            myLocalBranchesWithOutgoing.put(r, calculateBranchesWithOutgoing(r));
        }

        if (shouldRequestRemoteInfo && !withIncoming.isEmpty()) {
            GitIncomingRemoteCheckStrategy remoteCheckStrategy = getIncomingRemoteCheckStrategy();
            requestRemoteInfo(remoteCheckStrategy, withIncoming);
        }
        else {
            LOG.debug("No remote state refresh requested");
        }

        for (GitRepository r : withIncoming) {
            myLocalBranchesWithIncoming.put(r, calcBranchesWithIncoming(r));
        }
        if (!myProject.isDisposed()) {
            myProject.getMessageBus().syncPublisher(DvcsIncomingOutgoingListener.class).incomingOutgoingInfoChanged();
        }
    }

    private void requestRemoteInfo(GitIncomingRemoteCheckStrategy remoteCheckStrategy, List<GitRepository> repositories) {
        repositories.forEach(myLocalBranchesToFetch::remove);
        if (remoteCheckStrategy == GitIncomingRemoteCheckStrategy.NONE) {
            LOG.debug("Remote check disabled");
            return;
        }

        AtomicBoolean success = new AtomicBoolean(false);
        switch (remoteCheckStrategy) {
            case FETCH -> {
                LOG.info("Fetching %d repositories".formatted(repositories.size()));
                List<GitFetchSpec> remotesToFetch = new ArrayList<>();
                for (GitRepository repository : repositories) {
                    for (GitRemote remote : repository.getRemotes()) {
                        remotesToFetch.add(new GitFetchSpec(repository, remote, getAuthenticationMode(repository, remote)));
                    }
                }
                success.set(GitFetchSupport.fetchSupport(myProject).fetch(remotesToFetch).isSuccessful());
            }
            case LS_REMOTE -> {
                LOG.info("Listing remote info for %d repositories".formatted(repositories.size()));
                repositories.forEach(r -> myLocalBranchesToFetch.put(r, calculateBranchesToFetch(r, () -> success.set(false))));
            }
            default -> {
            }
        }
    }

    public DvcsInOutProjectState getState() {
        return shouldShow()
            ? new DvcsInOutProjectState(mapState(myLocalBranchesWithIncoming), mapState(myLocalBranchesWithOutgoing), myLastFetchTime.get())
            : DvcsInOutProjectState.EMPTY;
    }

    private static Map<FilePath, Map<String, Integer>> mapState(Map<GitRepository, Map<GitLocalBranch, Integer>> projectState) {
        Map<FilePath, Map<String, Integer>> result = new HashMap<>();
        projectState.forEach((repo, branches) -> result.put(VcsUtil.getFilePath(repo.getRoot()), remapLocalBranchToName(branches)));
        return result;
    }

    private static Map<String, Integer> remapLocalBranchToName(Map<GitLocalBranch, Integer> repoState) {
        Map<String, Integer> result = new HashMap<>();
        repoState.forEach((localBranch, commits) -> result.put(localBranch.getName(), commits));
        return result;
    }

    public Collection<GitLocalBranch> getBranchesWithIncoming(@Nullable GitRepository repository) {
        return getBranches(repository, myLocalBranchesWithIncoming);
    }

    public Collection<GitLocalBranch> getBranchesWithOutgoing(@Nullable GitRepository repository) {
        return getBranches(repository, myLocalBranchesWithOutgoing);
    }

    private void updateAllBranchesWithIncomingFromRemote() {
        markDirty(GitRepositoryManager.getInstance(myProject).getRepositories(), null, true);
    }

    private void updateAllBranchesWithOutgoing() {
        markDirty(null, GitRepositoryManager.getInstance(myProject).getRepositories(), false);
    }

    void markDirty(@Nullable Collection<GitRepository> withIncoming,
                   @Nullable Collection<GitRepository> withOutgoing,
                   boolean requestRemoteInfo) {
        synchronized (LOCK) {
            if (requestRemoteInfo) {
                myShouldRequestRemoteInfo = true;
            }
            if (withIncoming != null) {
                myDirtyReposWithIncoming.addAll(withIncoming);
            }
            if (withOutgoing != null) {
                myDirtyReposWithOutgoing.addAll(withOutgoing);
            }
        }
        scheduleUpdate();
    }

    private Map<GitLocalBranch, Hash> calculateBranchesToFetch(GitRepository repository, Runnable onError) {
        Map<GitLocalBranch, Hash> result = new HashMap<>();
        groupTrackInfoByRemotes(repository).entrySet()
            .forEach(entry -> result.putAll(calcBranchesToFetchForRemote(repository, entry.getKey(), entry.getValue(), onError)));
        return result;
    }

    private Map<GitLocalBranch, Hash> calcBranchesToFetchForRemote(GitRepository repository,
                                                                   GitRemote gitRemote,
                                                                   Collection<? extends GitBranchTrackInfo> trackInfoList,
                                                                   Runnable onError) {
        Map<GitLocalBranch, Hash> result = new HashMap<>();
        GitBranchesCollection branchesCollection = repository.getBranches();
        Map<String, Hash> remoteNameWithHash =
            lsRemote(repository, gitRemote, ContainerUtil.map(trackInfoList, info -> info.getRemoteBranch().getNameForRemoteOperations()), onError);

        for (Map.Entry<String, Hash> hashEntry : remoteNameWithHash.entrySet()) {
            String remoteBranchName = hashEntry.getKey();
            Hash remoteHash = hashEntry.getValue();
            trackInfoList.forEach(info -> {
                GitRemoteBranch remoteBranch = info.getRemoteBranch();
                Hash localHashForRemoteBranch = branchesCollection.getHash(remoteBranch);

                if (localHashForRemoteBranch == null) {
                    return;
                }

                if (StringUtil.equals(remoteBranchName, addRefsHeadsPrefixIfNeeded(remoteBranch.getNameForRemoteOperations()))) {
                    if (!localHashForRemoteBranch.equals(remoteHash)) {
                        result.put(info.getLocalBranch(), localHashForRemoteBranch);
                    }
                }
            });
        }
        return result;
    }

    private Map<GitLocalBranch, Integer> calcBranchesWithIncoming(GitRepository repository) {
        Map<GitLocalBranch, Integer> result = new HashMap<>();
        GitBranchesCollection branchesCollection = repository.getBranches();
        Map<GitLocalBranch, Hash> cachedBranchesToFetch = myLocalBranchesToFetch.get(repository);

        branchesCollection.getLocalBranches().forEach(localBranch -> {
            GitBranchTrackInfo info = GitBranchUtil.getTrackInfoForBranch(repository, localBranch);
            if (info == null) {
                return;
            }
            Hash localHashForRemoteBranch = branchesCollection.getHash(info.getRemoteBranch());
            Hash localHash = branchesCollection.getHash(localBranch);
            if (localHashForRemoteBranch == null) {
                return;
            }

            Integer commits = getCommitsForBranch(repository, info.getLocalBranch(), localHash, localHashForRemoteBranch, true);
            if (commits != null && commits > 0) {
                result.put(info.getLocalBranch(), commits);
            }
            else if (cachedBranchesToFetch != null && localHashForRemoteBranch.equals(cachedBranchesToFetch.get(localBranch))) {
                // In this case, we know that the remote branch has some new commits,
                // but they aren't fetched yet, so we can't count them
                // TODO: fetch?
                result.put(info.getLocalBranch(), 0);
            }
        });
        return result;
    }

    /**
     * {@link AuthenticationMode#NONE} is used until the first successful authentication on the remote to reduce risk
     * of showing OS password storage dialog.
     *
     * @see AuthenticationMode
     */
    private AuthenticationMode getAuthenticationMode(GitRepository repository, GitRemote remote) {
        return myAuthSuccessMap.get(repository).contains(remote) ? SILENT : NONE;
    }

    private static boolean shouldAvoidUserInteraction(GitRemote remote) {
        return ContainerUtil.exists(remote.getUrls(), GitRemoteUrlUtil::isSshUrl) && HAS_EXTERNAL_SSH_AGENT.get();
    }

    private Map<String, Hash> lsRemote(GitRepository repository, GitRemote remote, List<String> branchRefNames, Runnable onError) {
        Map<String, Hash> result = new HashMap<>();

        if (!supportsIncomingOutgoing()) {
            return result;
        }
        if (shouldAvoidUserInteraction(remote)) {
            return result;
        }

        VcsFileUtil.chunkArguments(branchRefNames).forEach(refs -> {
            List<String> params = ContainerUtil.concat(List.of("--heads", remote.getName()), refs);
            GitCommandResult lsRemoteResult = Git.getInstance().runCommand(() -> createLsRemoteHandler(repository, remote, params));
            if (lsRemoteResult.success()) {
                Map<String, String> hashWithNameMap = new HashMap<>();
                for (String line : lsRemoteResult.getOutput()) {
                    Pair<String, String> parsed = GitRefUtil.parseBranchesLine(line);
                    if (parsed != null) {
                        hashWithNameMap.put(parsed.first, parsed.second);
                    }
                }
                result.putAll(getResolvedHashes(hashWithNameMap));
                myAuthSuccessMap.putValue(repository, remote);
            }
            else {
                onError.run();
            }
        });
        return result;
    }

    private GitLineHandler createLsRemoteHandler(GitRepository repository, GitRemote remote, List<String> params) {
        GitLineHandler h = new GitLineHandler(myProject, repository.getRoot(), GitCommand.LS_REMOTE);
        h.setIgnoreAuthenticationMode(getAuthenticationMode(repository, remote));
        h.addParameters(params);
        h.setUrls(remote.getUrls());
        return h;
    }

    private Map<GitLocalBranch, Integer> calculateBranchesWithOutgoing(GitRepository gitRepository) {
        Map<GitLocalBranch, Integer> branchesWithOutgoing = new HashMap<>();
        GitBranchesCollection branchesCollection = gitRepository.getBranches();
        for (GitLocalBranch branch : branchesCollection.getLocalBranches()) {
            GitPushTarget pushTarget = GitPushSupport.getPushTargetIfExist(gitRepository, branch);
            Hash localHashForRemoteBranch = pushTarget != null ? branchesCollection.getHash(pushTarget.remoteBranch()) : null;
            Hash localHash = branchesCollection.getHash(branch);
            Integer commits = getCommitsForBranch(gitRepository, branch, localHash, localHashForRemoteBranch, false);
            if (commits != null && commits > 0) {
                branchesWithOutgoing.put(branch, commits);
            }
        }
        return branchesWithOutgoing;
    }

    private @Nullable Integer getCommitsForBranch(GitRepository repository,
                                                  GitLocalBranch localBranch,
                                                  @Nullable Hash localBranchHash,
                                                  @Nullable Hash localHashForRemoteBranch,
                                                  boolean incoming) {
        if (!supportsIncomingOutgoing()) {
            return null;
        }
        if (localHashForRemoteBranch == null || Objects.equals(localBranchHash, localHashForRemoteBranch)) {
            return null;
        }

        //run git rev-list --count pushTargetForBranch_or_hash..localName for outgoing ( @{push} can be used only for equal branch names)
        //see git-push help -> simple push strategy
        //git rev-list --count localName..localName@{u} for incoming
        String branchName = localBranch.getName();
        String from = incoming ? branchName : localHashForRemoteBranch.asString();
        String to = incoming ? branchName + "@{u}" : branchName;
        String numberOfCommitsBetween = GitHistoryUtils.getNumberOfCommitsBetween(repository, from, to);
        if (numberOfCommitsBetween == null) {
            LOG.warn("Can't get outgoing info (git rev-list " + branchName + " failed)");
            return null;
        }
        try {
            return Integer.parseInt(numberOfCommitsBetween);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private static Collection<GitLocalBranch> getBranches(@Nullable GitRepository repository,
                                                          Map<GitRepository, Map<GitLocalBranch, Integer>> branchCollection) {
        if (!shouldShow()) {
            return Collections.emptySet();
        }

        if (repository != null) {
            Map<GitLocalBranch, Integer> map = Objects.requireNonNullElse(branchCollection.get(repository), Collections.emptyMap());
            return map.keySet();
        }
        return branchCollection.values().stream()
            .filter(Objects::nonNull)
            .flatMap(map -> map.keySet().stream())
            .collect(Collectors.toSet());
    }

    @Override
    public void repositoryChanged(GitRepository repository) {
        Collection<GitRepository> repos = Collections.singleton(repository);
        markDirty(repos, repos, false);
    }

    @Override
    public void authenticationSucceeded(GitRepository repository, GitRemote remote) {
        if (getIncomingRemoteCheckStrategy() == GitIncomingRemoteCheckStrategy.NONE) {
            return;
        }
        myAuthSuccessMap.putValue(repository, remote);
    }

    private static MultiMap<GitRemote, GitBranchTrackInfo> groupTrackInfoByRemotes(GitRepository repository) {
        return ContainerUtil.groupBy(repository.getBranchTrackInfos(), GitBranchTrackInfo::getRemote);
    }

    public void updateForTests() {
        List<GitRepository> repositories = GitRepositoryManager.getInstance(myProject).getRepositories();
        myDirtyReposWithIncoming.addAll(repositories);
        myDirtyReposWithOutgoing.addAll(repositories);
        myShouldRequestRemoteInfo = true;
        runUpdate();
    }
}
