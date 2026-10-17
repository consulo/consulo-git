/*
 * Copyright 2000-2015 JetBrains s.r.o.
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

import consulo.application.progress.ProgressBuilderFactory;
import consulo.disposer.Disposable;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.Component;
import consulo.ui.MessageBoxBuilder;
import consulo.ui.MessageButtonRole;
import consulo.ui.TextBox;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.dialog.Dialog;
import consulo.ui.ex.dialog.DialogDescriptor;
import consulo.ui.ex.dialog.DialogService;
import consulo.ui.ex.dialog.DialogValue;
import consulo.ui.ex.dialog.action.DialogOkAction;
import consulo.ui.util.FormBuilder;
import consulo.util.concurrent.coroutine.Coroutine;
import consulo.util.concurrent.coroutine.step.CodeExecution;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.validators.GitRefNameValidator;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

class GitDefineRemoteDialog extends DialogDescriptor {
    private static final Logger LOG = LoggerFactory.getLogger(GitDefineRemoteDialog.class);

    @Nonnull
    private final GitRepository myRepository;
    @Nonnull
    private final Git myGit;

    @Nullable
    private TextBox myRemoteName;
    @Nullable
    private TextBox myRemoteUrl;
    @Nullable
    private Dialog myDialog;
    private boolean myValidated;

    private GitDefineRemoteDialog(@Nonnull GitRepository repository, @Nonnull Git git) {
        super(LocalizeValue.localizeTODO("Define Remote"));
        myRepository = repository;
        myGit = git;
    }

    @Nonnull
    @RequiredUIAccess
    static CompletableFuture<GitDefinedRemote> show(@Nonnull GitRepository repository, @Nonnull Git git) {
        Project project = repository.getProject();
        GitDefineRemoteDialog descriptor = new GitDefineRemoteDialog(repository, git);
        Dialog dialog = project.getApplication().getInstance(DialogService.class).build(descriptor);
        descriptor.myDialog = dialog;

        CompletableFuture<GitDefinedRemote> result = new CompletableFuture<>();
        dialog.showAsync().whenComplete((value, error) -> result.complete(
            descriptor.myValidated ? new GitDefinedRemote(descriptor.getRemoteName(), descriptor.getRemoteUrl()) : null
        ));
        return result;
    }

    @Nonnull
    @RequiredUIAccess
    @Override
    public Component createCenterComponent(@Nonnull Disposable uiDisposable) {
        TextBox remoteName = TextBox.create(GitRemote.ORIGIN_NAME);
        TextBox remoteUrl = TextBox.create();
        myRemoteName = remoteName;
        myRemoteUrl = remoteUrl;

        return FormBuilder.create()
            .addLabeled(LocalizeValue.localizeTODO("Name:"), remoteName)
            .addLabeled(LocalizeValue.localizeTODO("URL:"), remoteUrl)
            .build();
    }

    @Nullable
    @RequiredUIAccess
    @Override
    public Component getPreferredFocusedComponent() {
        return myRemoteUrl;
    }

    @Nonnull
    String getRemoteName() {
        TextBox remoteName = myRemoteName;
        return remoteName == null ? "" : StringUtil.notNullize(remoteName.getValue()).trim();
    }

    @Nonnull
    String getRemoteUrl() {
        TextBox remoteUrl = myRemoteUrl;
        return remoteUrl == null ? "" : StringUtil.notNullize(remoteUrl.getValue()).trim();
    }

    @RequiredUIAccess
    @Override
    public boolean canHandle(@Nonnull AnAction action, @Nullable DialogValue value) {
        if (!(action instanceof DialogOkAction) || myValidated) {
            return true;
        }

        UIAccess uiAccess = UIAccess.current();
        validateRemoteUnderModal(getRemoteName(), getRemoteUrl()).whenCompleteAsync((error, throwable) -> {
            if (throwable != null) {
                return;
            }
            if (error.isNotEmpty()) {
                MessageBoxBuilder.<Boolean>create()
                    .asError()
                    .title(LocalizeValue.localizeTODO("Invalid Remote"))
                    .text(error)
                    .button(MessageButtonRole.OK, Boolean.TRUE)
                    .asDefaultButton()
                    .showAsync();
                return;
            }

            myValidated = true;
            Dialog dialog = myDialog;
            if (dialog != null) {
                dialog.doOkAction(DialogValue.OK_VALUE);
            }
        }, uiAccess);
        return false;
    }

    @Nonnull
    @RequiredUIAccess
    private CompletableFuture<LocalizeValue> validateRemoteUnderModal(@Nonnull String name, @Nonnull String url) {
        if (url.isEmpty()) {
            LOG.warn("Invalid remote. Name: {}, URL: {}, error: {}", name, url, "URL can't be empty");
            return CompletableFuture.completedFuture(LocalizeValue.localizeTODO("URL can't be empty"));
        }
        if (!GitRefNameValidator.getInstance().checkInput(name)) {
            LOG.warn("Invalid remote. Name: {}, URL: {}, error: {}", name, url, "Remote name contains illegal characters");
            return CompletableFuture.completedFuture(LocalizeValue.localizeTODO("Remote name contains illegal characters"));
        }

        Project project = myRepository.getProject();
        return project.getApplication().getInstance(ProgressBuilderFactory.class)
            .newProgressBuilder(project, LocalizeValue.localizeTODO("Checking URL..."))
            .modal()
            .cancelable()
            .execute(UIAccess.current(), () -> Coroutine.first(CodeExecution.<Object, LocalizeValue>apply((input, continuation) -> {
                GitCommandResult result = myGit.lsRemote(project, VirtualFileUtil.virtualToIoFile(myRepository.getRoot()), url);
                if (!result.success()) {
                    LOG.warn(
                        "Invalid remote. Name: {}, URL: {}, error: {}",
                        name,
                        url,
                        "Remote URL test failed: " + result.getErrorOutputAsHtmlString()
                    );
                    return LocalizeValue.join(LocalizeValue.localizeTODO("Remote URL test failed: "), result.getErrorOutputAsHtmlValue());
                }
                return LocalizeValue.empty();
            })));
    }
}
