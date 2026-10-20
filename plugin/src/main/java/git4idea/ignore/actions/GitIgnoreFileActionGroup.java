// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.ignore.actions;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionParentRef;
import consulo.annotation.component.ActionRef;
import consulo.annotation.component.ActionRefAnchor;
import consulo.project.Project;
import consulo.ui.ex.action.AnAction;
import consulo.versionControlSystem.change.ignore.action.IgnoreFileActionGroup;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.ignore.lang.GitIgnoreFileType;

import java.util.List;

@ActionImpl(
    id = "Git.Ignore.File",
    parents = {
        @ActionParentRef(
            value = @ActionRef(id = "ChangesViewPopupMenu"),
            anchor = ActionRefAnchor.AFTER,
            relatedToAction = @ActionRef(id = "ChangesView.AddUnversioned")
        ),
        @ActionParentRef(
            value = @ActionRef(id = "Git.FileActions"),
            anchor = ActionRefAnchor.AFTER,
            relatedToAction = @ActionRef(id = "Git.Add")
        )
    }
)
public class GitIgnoreFileActionGroup extends IgnoreFileActionGroup {
    public GitIgnoreFileActionGroup() {
        super(GitIgnoreFileType.INSTANCE);
    }

    @Override
    protected List<AnAction> createAdditionalActions(
        Project project,
        List<VirtualFile> selectedFiles,
        List<VirtualFile> unversionedFiles
    ) {
        AddToGitExcludeAction action = new AddToGitExcludeAction();
        return action.isEnabled(project, selectedFiles, unversionedFiles) ? List.of(action) : List.of();
    }
}
