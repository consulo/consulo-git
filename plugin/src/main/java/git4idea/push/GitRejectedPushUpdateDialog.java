/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package git4idea.push;

import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.MessageBoxBuilder;
import consulo.ui.MessageBoxRemember;
import consulo.ui.MessageButtonRole;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.distributed.DvcsUtil;
import git4idea.GitBranch;
import git4idea.GitUtil;
import git4idea.config.UpdateMethod;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;
import org.jetbrains.annotations.TestOnly;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * @author Kirill Likhodedov
 */
class GitRejectedPushUpdateDialog {
    private static final LocalizeValue INDENT = LocalizeValue.of("&nbsp;&nbsp;&nbsp;&nbsp;");
    private static final LocalizeValue LINE_BREAK = LocalizeValue.of("<br/>");
    private static final LocalizeValue SPACE = LocalizeValue.of(" ");

    @Nonnull
    private final Project myProject;
    @Nonnull
    private final Collection<GitRepository> myRepositories;
    @Nonnull
    private final PushUpdateSettings myInitialSettings;
    private final boolean myRebaseOverMergeProblemDetected;
    private boolean myAutoUpdateInFuture;

    GitRejectedPushUpdateDialog(
        @Nonnull Project project,
        @Nonnull Collection<GitRepository> repositories,
        @Nonnull PushUpdateSettings initialSettings,
        boolean rebaseOverMergeProblemDetected
    ) {
        myProject = project;
        myRepositories = repositories;
        myInitialSettings = initialSettings;
        myRebaseOverMergeProblemDetected = rebaseOverMergeProblemDetected;
    }

    @Nonnull
    @RequiredUIAccess
    CompletableFuture<@Nullable UpdateMethod> showAsync() {
        boolean rebaseByDefault = !myRebaseOverMergeProblemDetected && myInitialSettings.updateMethod() == UpdateMethod.REBASE;

        MessageBoxBuilder<@Nullable UpdateMethod> builder = MessageBoxBuilder.<@Nullable UpdateMethod>create()
            .asWarning()
            .title(GitLocalize.pushRejectedDialogTitle())
            .text(makeDescription())
            .richText()
            .button(MessageButtonRole.YES, GitLocalize.pushRejectedMerge(), UpdateMethod.MERGE);
        if (!rebaseByDefault) {
            builder = builder.asDefaultButton();
        }

        LocalizeValue rebaseText = myRebaseOverMergeProblemDetected ? GitLocalize.pushRejectedRebaseAnyway() : GitLocalize.pushRejectedRebase();
        builder = builder.button(MessageButtonRole.NO, rebaseText, UpdateMethod.REBASE);
        if (rebaseByDefault) {
            builder = builder.asDefaultButton();
        }

        return builder.button(MessageButtonRole.CANCEL, (UpdateMethod) null)
            .asExitButton()
            .remember(new MessageBoxRemember<>() {
                @Override
                public void setValue(@Nullable UpdateMethod value) {
                    if (value != null) {
                        myAutoUpdateInFuture = true;
                    }
                }

                @Override
                public @Nullable UpdateMethod getValue() {
                    return null;
                }

                @Override
                public LocalizeValue getMessageText() {
                    return GitLocalize.pushRejectedRememberCheckbox();
                }
            })
            .showAsync();
    }

    boolean shouldAutoUpdateInFuture() {
        return myAutoUpdateInFuture;
    }

    @TestOnly
    boolean warnsAboutRebaseOverMerge() {
        return myRebaseOverMergeProblemDetected;
    }

    @Nonnull
    private LocalizeValue makeDescription() {
        List<LocalizeValue> parts = new ArrayList<>();
        if (GitUtil.justOneGitRepository(myProject)) {
            assert !myRepositories.isEmpty() : "repositories are empty";
            GitRepository repository = myRepositories.iterator().next();
            parts.add(GitLocalize.pushRejectedOnlyOneGitRepo(escape(getCurrentBranch(repository).getName())));
            parts.add(SPACE);
        }
        else if (myRepositories.size() == 1) {
            GitRepository repository = myRepositories.iterator().next();
            parts.add(GitLocalize.pushRejectedSpecificRepo(
                escape(getCurrentBranch(repository).getName()),
                escape(DvcsUtil.getShortRepositoryName(repository))
            ));
            parts.add(SPACE);
        }
        else {
            Map<GitRepository, GitBranch> currentBranches = getCurrentBranches();
            if (allBranchesHaveTheSameName(currentBranches)) {
                String branchName = currentBranches.values().iterator().next().getName();
                parts.add(GitLocalize.pushRejectedManyReposSingleBranch(escape(branchName)));
                parts.add(LINE_BREAK);
                for (GitRepository repository : DvcsUtil.sortRepositories(currentBranches.keySet())) {
                    parts.add(INDENT);
                    parts.add(LocalizeValue.of(escape(DvcsUtil.getShortRepositoryName(repository))));
                    parts.add(LINE_BREAK);
                }
            }
            else {
                parts.add(GitLocalize.pushRejectedManyRepos());
                parts.add(LINE_BREAK);
                for (Map.Entry<GitRepository, GitBranch> entry : currentBranches.entrySet()) {
                    parts.add(INDENT);
                    parts.add(GitLocalize.pushRejectedManyReposItem(
                        escape(entry.getValue().getName()),
                        escape(DvcsUtil.getShortRepositoryName(entry.getKey()))
                    ));
                    parts.add(LINE_BREAK);
                }
            }
        }

        parts.add(myRebaseOverMergeProblemDetected ? GitLocalize.pushRejectedMergeNeededWithProblem() : GitLocalize.pushRejectedMergeNeeded());
        return LocalizeValue.join(parts.toArray(LocalizeValue[]::new));
    }

    @Nonnull
    private static String escape(@Nonnull String text) {
        return StringUtil.escapeXmlEntities(text);
    }

    private static boolean allBranchesHaveTheSameName(@Nonnull Map<GitRepository, GitBranch> branches) {
        String name = null;
        for (GitBranch branch : branches.values()) {
            if (name == null) {
                name = branch.getName();
            }
            else if (!name.equals(branch.getName())) {
                return false;
            }
        }
        return true;
    }

    @Nonnull
    private Map<GitRepository, GitBranch> getCurrentBranches() {
        Map<GitRepository, GitBranch> currentBranches = new LinkedHashMap<>();
        for (GitRepository repository : myRepositories) {
            currentBranches.put(repository, getCurrentBranch(repository));
        }
        return currentBranches;
    }

    @Nonnull
    private static GitBranch getCurrentBranch(@Nonnull GitRepository repository) {
        GitBranch currentBranch = repository.getCurrentBranch();
        assert currentBranch != null : "Current branch can't be null here. " + repository;
        return currentBranch;
    }
}
