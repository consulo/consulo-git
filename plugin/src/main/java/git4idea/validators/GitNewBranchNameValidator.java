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

import consulo.ui.ex.InputValidatorEx;
import git4idea.GitBranch;
import git4idea.branch.GitBranchUtil;
import git4idea.branch.GitBranchesCollection;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;

import java.util.Collection;

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
public final class GitNewBranchNameValidator implements InputValidatorEx {

  private final Collection<GitRepository> myRepositories;
  private String myErrorText;

  private GitNewBranchNameValidator(@Nonnull Collection<GitRepository> repositories) {
    myRepositories = repositories;
  }

  public static GitNewBranchNameValidator newInstance(@Nonnull Collection<GitRepository> repositories) {
    return new GitNewBranchNameValidator(repositories);
  }

  @Override
  public boolean checkInput(String inputString) {
    if (!GitRefNameValidator.getInstance().checkInput(inputString)){
      myErrorText = "Invalid name for branch";
      return false;
    }
    return checkBranchConflict(inputString);
  }

  private boolean checkBranchConflict(String inputString) {
    if (isNotPermitted(inputString) || conflictsWithLocalBranch(inputString) || conflictsWithRemoteBranch(inputString)) {
      return false;
    }
    myErrorText = null;
    return true;
  }

  private boolean isNotPermitted(@Nonnull String inputString) {
    if (inputString.equalsIgnoreCase("head")) {
      myErrorText = "Branch name " + inputString + " is not valid";
      return true;
    }
    return false;
  }

  private boolean conflictsWithLocalBranch(String inputString) {
    return conflictsWithLocalOrRemote(inputString, true, " already exists");
  }

  private boolean conflictsWithRemoteBranch(String inputString) {
    return conflictsWithLocalOrRemote(inputString, false, " clashes with remote branch with the same name");
  }

  private boolean conflictsWithLocalOrRemote(String inputString, boolean local, String message) {
    for (GitRepository repository : myRepositories) {
      GitBranchesCollection branchesCollection = repository.getBranches();
      Collection<? extends GitBranch> branches = local ? branchesCollection.getLocalBranches() : branchesCollection.getRemoteBranches();
      for (GitBranch branch : branches) {
        if (branch.getName().equals(inputString)) {
          myErrorText = "Branch name " + inputString + message;
          if (myRepositories.size() > 1 && !allReposHaveBranch(inputString, local)) {
            myErrorText += " in repository " + repository.getPresentableUrl();
          }
          return true;
        }
      }
    }
    return false;
  }

  private boolean allReposHaveBranch(String inputString, boolean local) {
    for (GitRepository repository : myRepositories) {
      GitBranchesCollection branchesCollection = repository.getBranches();
      Collection<? extends GitBranch> branches = local ? branchesCollection.getLocalBranches() : branchesCollection.getRemoteBranches();
      if (!GitBranchUtil.convertBranchesToNames(branches).contains(inputString)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean canClose(String inputString) {
    return checkInput(inputString);
  }

  @Override
  public String getErrorText(String inputString) {
    return myErrorText;
  }
}
