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
package git4idea.remote.hosting.ui;

import consulo.git.localize.GitLocalize;
import consulo.platform.base.localize.CommonLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.versionControlSystem.CommitMessageComponent;
import consulo.versionControlSystem.CommitMessageFactory;
import consulo.versionControlSystem.ui.SelectFilesDialogBuilder;
import consulo.versionControlSystem.ui.VcsChangesDialogFactory;
import consulo.virtualFileSystem.VirtualFile;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ShareProjectUntrackedFilesDialog {
    private final Project myProject;
    private final List<VirtualFile> myUntrackedFiles;
    private @Nullable List<VirtualFile> mySelectedFiles;

    public ShareProjectUntrackedFilesDialog(Project project, List<VirtualFile> untrackedFiles) {
        myProject = project;
        myUntrackedFiles = untrackedFiles;
    }

    public void setSelectedFiles(List<VirtualFile> selectedFiles) {
        mySelectedFiles = selectedFiles;
    }

    /**
     * @return the checked files and the commit message, or an empty optional when the user cancelled
     */
    @RequiredUIAccess
    public CompletableFuture<Optional<ShareProjectUntrackedFilesDialogResult>> showAsync() {
        CommitMessageComponent commitMessage = myProject.getInstance(CommitMessageFactory.class).create(false);
        commitMessage.setCommitMessage("Initial commit");

        SelectFilesDialogBuilder<VirtualFile> builder = myProject.getApplication().getInstance(VcsChangesDialogFactory.class)
            .selectFiles(myProject, myUntrackedFiles)
            .title(GitLocalize.shareUntrackedFilesDialogTitle())
            .selectable()
            .okText(CommonLocalize.buttonAdd())
            .cancelText(CommonLocalize.buttonCancel())
            .bottomComponent(uiDisposable -> commitMessage.getComponent())
            .dimensionServiceKey("Git.ShareProject.UntrackedFilesDialog");
        List<VirtualFile> selectedFiles = mySelectedFiles;
        if (selectedFiles != null) {
            builder.selected(selectedFiles);
        }
        return builder.showAsync()
            .thenApply(files -> files.map(it -> new ShareProjectUntrackedFilesDialogResult(it, commitMessage.getCommitMessage())));
    }
}
