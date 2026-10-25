// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.validators;

import consulo.git.localize.GitBranchesLocalize;
import consulo.localize.LocalizeValue;
import consulo.ui.InputProblem;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.distributed.DvcsUtil;
import git4idea.GitBranch;
import git4idea.GitUtil;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public final class GitBranchValidator {
    private GitBranchValidator() {
    }

    public static @Nullable InputProblem validateName(Collection<GitRepository> repositories, String inputString) {
        InputProblem problem = checkRefName(inputString);
        return problem != null ? problem : checkBranchConflict(repositories, inputString);
    }

    public static @Nullable InputProblem checkRefName(@Nullable String inputString) {
        InputProblem problem = checkRefNameEmptyOrHead(inputString);
        if (problem != null) {
            return problem;
        }
        if (!GitRefNameValidator.getInstance().checkInput(inputString)) {
            return InputProblem.error(GitBranchesLocalize.newBranchDialogErrorBranchNameInvalid(String.valueOf(inputString)));
        }
        return null;
    }

    public static @Nullable InputProblem checkRefNameEmptyOrHead(@Nullable String inputString) {
        if (StringUtil.isEmptyOrSpaces(inputString)) {
            return InputProblem.error(GitBranchesLocalize.newBranchDialogErrorBranchNameEmpty());
        }
        else if (StringUtil.equalsIgnoreCase(inputString, GitUtil.HEAD)) {
            return InputProblem.error(GitBranchesLocalize.newBranchDialogErrorBranchNameHead());
        }
        return null;
    }

    private static @Nullable InputProblem checkBranchConflict(Collection<GitRepository> repositories, String inputString) {
        InputProblem problem = conflictsWithLocalBranch(repositories, inputString);
        return problem != null ? problem : conflictsWithRemoteBranch(repositories, inputString);
    }

    public static @Nullable InputProblem conflictsWithLocalBranch(Collection<GitRepository> repositories, String inputString) {
        return conflictsWithLocalOrRemote(repositories, inputString, true, GitBranchesLocalize::newBranchDialogErrorBranchAlreadyExists);
    }

    public static @Nullable InputProblem conflictsWithRemoteBranch(Collection<GitRepository> repositories, String inputString) {
        return conflictsWithLocalOrRemote(repositories, inputString, false, GitBranchesLocalize::newBranchDialogErrorBranchClashesWithRemote);
    }

    public static @Nullable InputProblem conflictsWithLocalBranchDirectory(Set<String> directories, String inputString) {
        if (directories.contains(inputString)) {
            return InputProblem.error(GitBranchesLocalize.newBranchDialogErrorBranchClashesWithDirectory(inputString));
        }
        return null;
    }

    private static @Nullable InputProblem conflictsWithLocalOrRemote(Collection<GitRepository> repositories,
                                                                     String inputString,
                                                                     boolean local,
                                                                     Function<String, LocalizeValue> message) {
        List<GitRepository> reposWithConflictingBranch = getReposWithConflictingBranch(repositories, inputString, local);
        if (reposWithConflictingBranch.isEmpty()) {
            return null;
        }
        LocalizeValue errorText = message.apply(inputString);
        if (repositories.size() > reposWithConflictingBranch.size()) {
            errorText = LocalizeValue.join(errorText, getAdditionalDescription(reposWithConflictingBranch));
            if (local) {
                return InputProblem.warning(errorText);
            }
        }
        return InputProblem.error(errorText);
    }

    private static LocalizeValue getAdditionalDescription(List<GitRepository> repositories) {
        return LocalizeValue.join(
            LocalizeValue.of(" "),
            repositories.size() > 1
                ? GitBranchesLocalize.commonSuffixInSeveralRepositories(repositories.size())
                : GitBranchesLocalize.commonSuffixInOneRepository(DvcsUtil.getShortRepositoryName(repositories.get(0)))
        );
    }

    private static List<GitRepository> getReposWithConflictingBranch(Collection<GitRepository> repositories, String inputString, boolean local) {
        List<GitRepository> result = new ArrayList<>();
        for (GitRepository repository : repositories) {
            if (findConflictingBranch(inputString, repository, local) != null) {
                result.add(repository);
            }
        }
        return result;
    }

    private static @Nullable GitBranch findConflictingBranch(String inputString, GitRepository repository, boolean local) {
        return local ? repository.getBranches().findLocalBranch(inputString) : repository.getBranches().findRemoteBranch(inputString);
    }
}
