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
package git4idea.workingTrees.ui;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.HelpManager;
import consulo.component.messagebus.MessageBusConnection;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.ActionGroup;
import consulo.ui.ex.action.ActionManager;
import consulo.versionControlSystem.distributed.repository.VcsRepositoryMappingListener;
import consulo.versionControlSystem.workspace.VcsWorkspace;
import consulo.versionControlSystem.workspace.VcsWorkspaceLink;
import consulo.versionControlSystem.workspace.VcsWorkspaceProvider;
import git4idea.GitStandardLocalBranch;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryChangeListener;
import git4idea.repo.GitWorkingTreesListener;
import git4idea.ui.branch.GitBranchReview;
import git4idea.ui.branch.GitBranchReviewPresenter;
import git4idea.workingTrees.GitCreateWorkingTreeService;
import git4idea.workingTrees.GitWorkingTreesService;
import git4idea.workingTrees.GitWorkingTreesUtil;
import git4idea.workingTrees.ui.actions.GitWorkingTreesTabPopupGroup;
import git4idea.workingTrees.ui.actions.GitWorkingTreesTabToolbarGroup;
import git4idea.workingTrees.ui.actions.OpenWorkingTreeAction;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The git worktrees as the platform's workspace view, recomputed on every repository update and every change to
 * {@link GitCreateWorkingTreeService#getPendingCreations()} - the latter lets a worktree still being created appear as a
 * {@link GitWorktreeCreatingRow} immediately, without waiting for a repository update.
 */
@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitWorkingTreesWorkspaceProvider implements VcsWorkspaceProvider, Disposable {
    private final Project myProject;
    private final List<Runnable> myListeners = new CopyOnWriteArrayList<>();

    private final Object myReviewLock = new Object();
    private final Map<GitWorktreeBranchKey, @Nullable GitBranchReview> myReviews = new HashMap<>();
    /**
     * One lookup per repository with live branch keys.
     */
    private final Map<GitRepository, CompletableFuture<?>> myReviewJobs = new HashMap<>();
    /**
     * The branch set each {@link #myReviewJobs} entry was started with.
     */
    private Map<GitRepository, Set<GitStandardLocalBranch>> myReviewBranchesByRepository = Map.of();

    @Inject
    public GitWorkingTreesWorkspaceProvider(Project project) {
        myProject = project;

        MessageBusConnection connection = project.getMessageBus().connect(this);
        connection.subscribe(GitWorkingTreesListener.class, repository -> fireChanged());
        connection.subscribe(GitRepositoryChangeListener.class, repository -> fireChanged());
        connection.subscribe(VcsRepositoryMappingListener.class, this::fireChanged);
        GitCreateWorkingTreeService.getInstance().addPendingCreationsListener(this::fireChanged, this);
    }

    public static GitWorkingTreesWorkspaceProvider getInstance(Project project) {
        return project.getInstance(GitWorkingTreesWorkspaceProvider.class);
    }

    @Override
    public String getId() {
        return GitWorkingTreesUtil.TOOLWINDOW_TAB_ID;
    }

    @Override
    public LocalizeValue getFeatureName() {
        return GitLocalize.toolwindowWorkingTreesTabName();
    }

    @Override
    public boolean isTabAvailable() {
        return GitWorkingTreesService.getInstance(myProject).shouldWorkingTreesTabBeShown();
    }

    @Override
    public void tabClosedByUser() {
        GitWorkingTreesService.getInstance(myProject).workingTreesTabClosedByUser();
    }

    @Override
    public List<GitWorktreeGroup> getGroups() {
        Map<GitWorktreeBranchKey, @Nullable GitBranchReview> reviews;
        synchronized (myReviewLock) {
            reviews = new HashMap<>(myReviews);
        }
        List<GitWorktreeGroup> groups =
            GitWorktreesUiUtil.buildEntries(myProject, GitCreateWorkingTreeService.getInstance().getPendingCreations(), reviews::get);
        reconcileReviewJobs(toBranchKeys(groups));
        return groups;
    }

    @Override
    public void addChangeListener(Runnable listener, Disposable parentDisposable) {
        myListeners.add(listener);
        Disposer.register(parentDisposable, () -> myListeners.remove(listener));
    }

    private void fireChanged() {
        myListeners.forEach(Runnable::run);
    }

    @Override
    public String getActionPlace() {
        return GitWorkingTreesContentFactory.GIT_WORKING_TREE_TOOLWINDOW_TAB_TOOLBAR;
    }

    @Override
    public ActionGroup getToolbarActions() {
        return ActionManager.getInstance().getAction(GitWorkingTreesTabToolbarGroup.class);
    }

    @Override
    public ActionGroup getPopupActions() {
        return ActionManager.getInstance().getAction(GitWorkingTreesTabPopupGroup.class);
    }

    @RequiredUIAccess
    @Override
    public void open(VcsWorkspace workspace) {
        if (workspace instanceof GitWorktreeRow row) {
            OpenWorkingTreeAction.open(myProject, List.of(row.gitWorkingTree()));
        }
    }

    @Override
    public LocalizeValue getEmptyText() {
        return GitLocalize.toolwindowWorkingTreesTabEmptyText();
    }

    @Override
    public List<VcsWorkspaceLink> getEmptyLinks() {
        List<VcsWorkspaceLink> links = new ArrayList<>();
        List<GitRepository> repositories = GitWorktreesUiUtil.getRepositories(myProject);
        if (repositories.size() == 1) {
            GitRepository repository = repositories.getFirst();
            links.add(new VcsWorkspaceLink(GitLocalize.toolwindowWorkingTreesTabEmptyTextCreateWorkingTree(), null, () ->
                GitCreateWorkingTreeService.getInstance().collectDataAndCreateWorkingTree(repository, repository.getCurrentBranch())
            ));
        }
        links.add(new VcsWorkspaceLink(GitLocalize.toolwindowWorkingTreesTabEmptyWhatGitWorktree(), PlatformIconGroup.generalContexthelp(), () ->
            HelpManager.getInstance().invokeHelp(GitWorkingTreesContentFactory.EMPTY_TAB_WORKING_TREE_CONCEPT_HELP_ID)
        ));
        return links;
    }

    @Override
    public @Nullable String getHelpId() {
        return GitWorkingTreesContentFactory.TOOLWINDOW_CONTENT_HELP_ID;
    }

    private static Set<GitWorktreeBranchKey> toBranchKeys(List<GitWorktreeGroup> groups) {
        Set<GitWorktreeBranchKey> keys = new HashSet<>();
        for (GitWorktreeGroup group : groups) {
            for (GitWorkingTreesListEntry entry : group.entries()) {
                if (entry instanceof GitWorktreeRow row) {
                    GitWorktreeBranchKey key = GitWorktreeBranchKey.resolveReviewBranchKey(row);
                    if (key != null) {
                        keys.add(key);
                    }
                }
            }
        }
        return keys;
    }

    /**
     * Starts a lookup for every repository whose branch set in {@code keys} is new or has changed, and cancels and
     * removes the lookup for every repository that dropped out of {@code keys} entirely, leaving a repository's lookup
     * untouched when its branch set did not change - so an unrelated repository's worktree appearing or disappearing
     * never re-triggers, or discards the result of, another repository's PR/MR lookup. One {@link GitBranchReviewPresenter}
     * lookup covers every branch of a repository at once, so a hosting service can batch the lookup instead
     * of running one call per branch.
     */
    private void reconcileReviewJobs(Set<GitWorktreeBranchKey> keys) {
        Map<GitRepository, Set<GitStandardLocalBranch>> newBranchesByRepository = new HashMap<>();
        for (GitWorktreeBranchKey key : keys) {
            newBranchesByRepository.computeIfAbsent(key.repository(), it -> new HashSet<>()).add(key.branch());
        }

        synchronized (myReviewLock) {
            for (Map.Entry<GitRepository, Set<GitStandardLocalBranch>> entry : myReviewBranchesByRepository.entrySet()) {
                if (newBranchesByRepository.containsKey(entry.getKey())) {
                    continue;
                }
                CompletableFuture<?> job = myReviewJobs.remove(entry.getKey());
                if (job != null) {
                    job.cancel(false);
                }
                for (GitStandardLocalBranch branch : entry.getValue()) {
                    myReviews.remove(new GitWorktreeBranchKey(entry.getKey(), branch));
                }
            }

            for (Map.Entry<GitRepository, Set<GitStandardLocalBranch>> entry : newBranchesByRepository.entrySet()) {
                GitRepository repository = entry.getKey();
                Set<GitStandardLocalBranch> branches = entry.getValue();
                Set<GitStandardLocalBranch> previous = myReviewBranchesByRepository.get(repository);
                if (Objects.equals(previous, branches)) {
                    continue;
                }

                CompletableFuture<?> oldJob = myReviewJobs.remove(repository);
                if (oldJob != null) {
                    oldJob.cancel(false);
                }
                if (previous != null) {
                    for (GitStandardLocalBranch branch : previous) {
                        if (!branches.contains(branch)) {
                            myReviews.remove(new GitWorktreeBranchKey(repository, branch));
                        }
                    }
                }

                CompletableFuture<Map<GitStandardLocalBranch, @Nullable GitBranchReview>> job =
                    GitBranchReviewPresenter.findReviews(repository, branches);
                myReviewJobs.put(repository, job);
                job.whenComplete((reviewsByBranch, error) -> {
                    if (error != null || reviewsByBranch == null) {
                        return;
                    }
                    boolean changed = false;
                    synchronized (myReviewLock) {
                        if (myReviewJobs.get(repository) != job) {
                            return;
                        }
                        for (Map.Entry<GitStandardLocalBranch, @Nullable GitBranchReview> review : reviewsByBranch.entrySet()) {
                            GitWorktreeBranchKey key = new GitWorktreeBranchKey(repository, review.getKey());
                            if (!Objects.equals(myReviews.get(key), review.getValue())) {
                                myReviews.put(key, review.getValue());
                                changed = true;
                            }
                        }
                    }
                    if (changed) {
                        fireChanged();
                    }
                });
            }

            myReviewBranchesByRepository = newBranchesByRepository;
        }
    }

    @Override
    public void dispose() {
        synchronized (myReviewLock) {
            myReviewJobs.values().forEach(it -> it.cancel(false));
            myReviewJobs.clear();
        }
    }
}
