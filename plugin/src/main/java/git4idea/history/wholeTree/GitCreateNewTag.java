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
package git4idea.history.wholeTree;

import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.ui.InputBoxBuilder;
import consulo.ui.InputProblem;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.lang.StringUtil;
import git4idea.branch.GitBrancher;
import git4idea.repo.GitRepository;

import java.util.Collections;

/**
 * @author Irina.Chernushina
 * @since 2011-10-26
 */
public class GitCreateNewTag {
    private final Project myProject;
    private final GitRepository myRepository;
    private final String myReference;
    private final Runnable myCallInAwtAfterExecution;

    public GitCreateNewTag(Project project, GitRepository repository, String reference, Runnable callInAwtAfterExecution) {
        myProject = project;
        myRepository = repository;
        myReference = reference;
        myCallInAwtAfterExecution = callInAwtAfterExecution;
    }

    @RequiredUIAccess
    public void execute() {
        InputBoxBuilder.text()
            .asQuestion()
            .title(GitLocalize.gitNewTagDialogTitle(myReference))
            .text(GitLocalize.gitNewTagDialogTagNameLabel())
            .validator(value -> StringUtil.isEmpty(value) || StringUtil.containsWhitespaces(value)
                ? InputProblem.error(GitLocalize.gitNewTagDialogTagNameInvalid())
                : null)
            .showAsync(myProject)
            .whenCompleteAsync((name, error) -> {
                if (name != null) {
                    GitBrancher brancher = myProject.getInstance(GitBrancher.class);
                    brancher.createNewTag(name, myReference, Collections.singletonList(myRepository), myCallInAwtAfterExecution);
                }
            }, UIAccess.current());
    }
}
