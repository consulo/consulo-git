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
package git4idea.validators;

import consulo.git.localize.GitBranchesLocalize;
import consulo.localize.LocalizeValue;
import consulo.ui.InputProblem;
import consulo.ui.InputValidator;
import consulo.util.lang.StringUtil;
import git4idea.GitBranch;
import git4idea.branch.GitBranchesCollection;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * <p>
 *   In addition to {@link GitRefNameValidator} checks that the entered branch name doesn't conflict
 *   with any existing local or remote branch.
 * </p>
 * <p>Use it when creating new branch.</p>
 * <p>
 *   If several repositories are specified (to create a branch in all of them at once, for example), all branches of all repositories are
 *   checked for conflicts.
 * </p>
 *
 * @author Kirill Likhodedov
 */
public final class GitNewBranchNameValidator implements InputValidator<String> {
  private final Collection<GitRepository> myRepositories;

  private GitNewBranchNameValidator(@Nonnull Collection<GitRepository> repositories) {
    myRepositories = repositories;
  }

  public static GitNewBranchNameValidator newInstance(@Nonnull Collection<GitRepository> repositories) {
    return new GitNewBranchNameValidator(repositories);
  }

  @Nullable
  @Override
  public InputProblem validate(String inputString) {
    InputProblem problem = checkRefName(inputString);
    return problem != null ? problem : checkBranchConflict(inputString);
  }

  @Nullable
  private static InputProblem checkRefName(String inputString) {
    if (StringUtil.isEmptyOrSpaces(inputString)) {
      return InputProblem.error(GitBranchesLocalize.newBranchDialogErrorBranchNameEmpty());
    }
    if (inputString.equalsIgnoreCase("head")) {
      return InputProblem.error(GitBranchesLocalize.newBranchDialogErrorBranchNameHead());
    }
    if (!GitRefNameValidator.getInstance().checkInput(inputString)) {
      return InputProblem.error(GitBranchesLocalize.newBranchDialogErrorBranchNameInvalid(inputString));
    }
    return null;
  }

  @Nullable
  private InputProblem checkBranchConflict(String inputString) {
    InputProblem problem = conflictsWithLocalOrRemote(inputString, true, GitBranchesLocalize::newBranchDialogErrorBranchAlreadyExists);
    return problem != null
      ? problem
      : conflictsWithLocalOrRemote(inputString, false, GitBranchesLocalize::newBranchDialogErrorBranchClashesWithRemote);
  }

  @Nullable
  private InputProblem conflictsWithLocalOrRemote(String inputString, boolean local, Function<String, LocalizeValue> message) {
    List<GitRepository> conflicting = new ArrayList<>();
    for (GitRepository repository : myRepositories) {
      GitBranchesCollection branchesCollection = repository.getBranches();
      Collection<? extends GitBranch> branches = local ? branchesCollection.getLocalBranches() : branchesCollection.getRemoteBranches();
      for (GitBranch branch : branches) {
        if (branch.getName().equals(inputString)) {
          conflicting.add(repository);
          break;
        }
      }
    }
    if (conflicting.isEmpty()) {
      return null;
    }
    LocalizeValue text = message.apply(inputString);
    if (myRepositories.size() > conflicting.size()) {
      String repositories = StringUtil.join(conflicting, GitRepository::getPresentableUrl, ", ");
      LocalizeValue partial = GitBranchesLocalize.newBranchDialogErrorBranchInRepository(text, repositories);
      return local ? InputProblem.warning(partial) : InputProblem.error(partial);
    }
    return InputProblem.error(text);
  }
}
