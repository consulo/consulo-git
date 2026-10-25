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

import consulo.git.localize.GitBranchesLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.InputProblem;
import consulo.ui.NotificationType;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.versionControlSystem.distributed.MultiRootBranches;
import consulo.versionControlSystem.distributed.branch.DvcsNewBranchDialogBuilder;
import consulo.versionControlSystem.distributed.branch.DvcsNewBranchDialogFactory;
import consulo.versionControlSystem.distributed.branch.DvcsNewBranchInput;
import consulo.versionControlSystem.distributed.branch.DvcsNewBranchOption;
import git4idea.GitBranch;
import git4idea.config.GitVcsSettings;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.validators.GitBranchValidator;
import git4idea.validators.GitRefNameValidator;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class GitNewBranchDialog {
    private static final char NAME_SEPARATOR = '/';

    static Collection<String> collectDirectories(Iterable<String> branchNames, boolean withTrailingSlash) {
        Set<String> directories = new LinkedHashSet<>();
        for (String branchName : branchNames) {
            if (branchName.indexOf(NAME_SEPARATOR) >= 0) {
                int index = 0;
                while (index < branchName.length()) {
                    int end = branchName.indexOf(NAME_SEPARATOR, index);
                    if (end == -1) {
                        break;
                    }
                    directories.add(withTrailingSlash ? branchName.substring(0, end + 1) : branchName.substring(0, end));
                    index = end + 1;
                }
            }
        }
        return directories;
    }

    private final Project myProject;
    private final Collection<GitRepository> myRepositories;
    private final LocalizeValue myDialogTitle;
    private final @Nullable String myInitialName;
    private final boolean myShowCheckOutOption;
    private final boolean myShowResetOption;
    private final boolean myShowSetTrackingOption;
    private final boolean myShowUnsetUpstreamOption;
    private final boolean myLocalConflictsAllowed;
    private final GitBranchOperationType myOperation;

    private final GitRefNameValidator myValidator = GitRefNameValidator.getInstance();
    private final Set<String> myLocalBranchDirectories;
    private final List<GitRepository> myAllRepositories;

    public GitNewBranchDialog(Project project, Collection<GitRepository> repositories, LocalizeValue dialogTitle, @Nullable String initialName) {
        this(project, repositories, dialogTitle, initialName, true);
    }

    public GitNewBranchDialog(Project project,
                              Collection<GitRepository> repositories,
                              LocalizeValue dialogTitle,
                              @Nullable String initialName,
                              boolean showCheckOutOption) {
        this(project, repositories, dialogTitle, initialName, showCheckOutOption, false);
    }

    public GitNewBranchDialog(Project project,
                              Collection<GitRepository> repositories,
                              LocalizeValue dialogTitle,
                              @Nullable String initialName,
                              boolean showCheckOutOption,
                              boolean showResetOption) {
        this(project, repositories, dialogTitle, initialName, showCheckOutOption, showResetOption, false);
    }

    public GitNewBranchDialog(Project project,
                              Collection<GitRepository> repositories,
                              LocalizeValue dialogTitle,
                              @Nullable String initialName,
                              boolean showCheckOutOption,
                              boolean showResetOption,
                              boolean showSetTrackingOption) {
        this(
            project,
            repositories,
            dialogTitle,
            initialName,
            showCheckOutOption,
            showResetOption,
            showSetTrackingOption,
            false,
            false,
            showCheckOutOption ? GitBranchOperationType.CREATE : GitBranchOperationType.CHECKOUT
        );
    }

    public GitNewBranchDialog(Project project,
                              Collection<GitRepository> repositories,
                              LocalizeValue dialogTitle,
                              @Nullable String initialName,
                              boolean showCheckOutOption,
                              boolean showResetOption,
                              boolean showSetTrackingOption,
                              boolean showUnsetUpstreamOption,
                              boolean localConflictsAllowed,
                              GitBranchOperationType operation) {
        myProject = project;
        myRepositories = repositories;
        myDialogTitle = dialogTitle;
        myInitialName = initialName;
        myShowCheckOutOption = showCheckOutOption;
        myShowResetOption = showResetOption;
        myShowSetTrackingOption = showSetTrackingOption;
        myShowUnsetUpstreamOption = showUnsetUpstreamOption;
        myLocalConflictsAllowed = localConflictsAllowed;
        myOperation = operation;
        myLocalBranchDirectories = new LinkedHashSet<>(collectDirectories(collectLocalBranchNames(), false));
        myAllRepositories = GitRepositoryManager.getInstance(project).getRepositories();
    }

    @RequiredUIAccess
    public CompletableFuture<@Nullable GitNewBranchOptions> showAndGetOptions() {
        DvcsNewBranchOption<GitRepository> checkoutOption =
            DvcsNewBranchOption.of(GitBranchesLocalize.newBranchDialogCheckoutBranchCheckbox(), true);
        DvcsNewBranchOption<GitRepository> overwriteOption = DvcsNewBranchOption.of(
            GitBranchesLocalize.newBranchDialogOverwriteExistingBranchCheckbox(),
            false,
            input -> GitBranchValidator.conflictsWithLocalBranch(input.getRepositories(), cleanName(input)) != null
        );
        DvcsNewBranchOption<GitRepository> trackingOption =
            DvcsNewBranchOption.of(GitBranchesLocalize.newBranchDialogSetTrackingBranchCheckbox(), myShowSetTrackingOption);
        DvcsNewBranchOption<GitRepository> unsetUpstreamOption =
            DvcsNewBranchOption.of(GitBranchesLocalize.newBranchDialogUnsetUpstreamBranchCheckbox(), false);

        DvcsNewBranchDialogBuilder<GitRepository> builder = DvcsNewBranchDialogFactory.getInstance(myProject)
            .newBuilder(myRepositories)
            .title(myDialogTitle)
            .okText(myOperation.getText())
            .initialName(myInitialName)
            .nameCleaner(myValidator::cleanUpBranchNameOnTyping)
            .validator((input, onApply) -> validateBranchName(input, onApply, overwriteOption));

        if (myOperation != GitBranchOperationType.RENAME) {
            boolean syncEnabled = MultiRootBranches.isSyncOptionEnabled(GitVcsSettings.getInstance(myProject));
            builder.repositoryChooser(
                myAllRepositories,
                syncEnabled ? GitBranchesLocalize.newBranchDialogBranchRootAllOverrideWarning() : null
            );
        }
        if (myShowCheckOutOption) {
            builder.option(checkoutOption);
        }
        if (myShowResetOption) {
            builder.option(overwriteOption);
        }
        if (myShowSetTrackingOption) {
            builder.option(trackingOption);
        }
        if (myShowUnsetUpstreamOption) {
            builder.option(unsetUpstreamOption);
        }

        return builder.showAsync().thenApply(input -> {
            if (input == null) {
                return null;
            }
            return new GitNewBranchOptions(
                cleanName(input),
                !myShowCheckOutOption || input.isSelected(checkoutOption),
                myShowResetOption && input.isSelected(overwriteOption),
                myShowSetTrackingOption && input.isSelected(trackingOption),
                input.getRepositories(),
                myShowUnsetUpstreamOption && input.isSelected(unsetUpstreamOption)
            );
        });
    }

    private String cleanName(DvcsNewBranchInput<GitRepository> input) {
        return myValidator.cleanUpBranchName(input.getName()).trim();
    }

    private List<String> collectLocalBranchNames() {
        List<String> names = new ArrayList<>();
        for (GitRepository repository : myRepositories) {
            for (GitBranch branch : repository.getBranches().getLocalBranches()) {
                names.add(branch.getName());
            }
        }
        return names;
    }

    private @Nullable InputProblem validateBranchName(DvcsNewBranchInput<GitRepository> input,
                                                      boolean onApply,
                                                      DvcsNewBranchOption<GitRepository> overwriteOption) {
        Collection<GitRepository> selectedRepositories = input.getRepositories();

        String branchName = cleanName(input);
        InputProblem errorInfo = onApply ? GitBranchValidator.checkRefNameEmptyOrHead(branchName) : null;
        if (errorInfo == null) {
            errorInfo = GitBranchValidator.conflictsWithRemoteBranch(selectedRepositories, branchName);
        }
        if (errorInfo == null) {
            errorInfo = GitBranchValidator.conflictsWithLocalBranchDirectory(myLocalBranchDirectories, branchName);
        }
        if (errorInfo != null) {
            return InputProblem.error(errorInfo.message());
        }

        InputProblem localBranchConflict = GitBranchValidator.conflictsWithLocalBranch(selectedRepositories, branchName);
        if (localBranchConflict == null || (myShowResetOption && input.isSelected(overwriteOption))) {
            return null;
        }
        else if (localBranchConflict.type() == NotificationType.WARNING && myLocalConflictsAllowed) {
            return InputProblem.warning(LocalizeValue.join(localBranchConflict.message(), LocalizeValue.of(".<br/>"), myOperation.getDescription()));
        }
        else if (myShowResetOption) {
            return InputProblem.error(LocalizeValue.join(
                localBranchConflict.message(),
                LocalizeValue.of(".<br/>"),
                GitBranchesLocalize.newBranchDialogOverwriteExistingBranchWarning()
            ));
        }
        else {
            return InputProblem.error(localBranchConflict.message());
        }
    }
}
