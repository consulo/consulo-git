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
package git4idea.checkout;

import consulo.disposer.Disposable;
import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.fileChooser.FileChooserTextBoxBuilder;
import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.util.FormBuilder;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.checkout.CheckoutCallback;
import consulo.versionControlSystem.distributed.DvcsRememberedInputs;
import consulo.versionControlSystem.distributed.ui.DvcsCheckoutPage;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandlerPasswordRequestAware;
import git4idea.commands.GitTask;
import git4idea.commands.GitTaskResult;
import git4idea.commands.GitTaskResultHandlerAdapter;
import git4idea.config.GitVcsApplicationSettings;
import git4idea.remote.GitRememberedInputs;
import git4idea.ui.GitShallowCloneComponentFactory;
import git4idea.ui.GitShallowCloneViewModel;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.util.function.Consumer;

/**
 * @author VISTALL
 * @since 2026-08-25
 */
public class GitCheckoutPage extends DvcsCheckoutPage {
    private final Git myGit;

    private FileChooserTextBoxBuilder.@Nullable Controller myPuttyKeyController;
    private final GitShallowCloneViewModel myShallowCloneViewModel = new GitShallowCloneViewModel();

    public GitCheckoutPage(Project project, Disposable uiDisposable, Git git) {
        super(project, uiDisposable, GitLocalize.git4ideaVcsName(), GitUtil.DOT_GIT);
        myGit = git;
    }

    @Override
    protected DvcsRememberedInputs getRememberedInputs() {
        return GitRememberedInputs.getInstance();
    }

    @Override
    @RequiredUIAccess
    protected void addCustomRows(FormBuilder builder, Context context) {
        builder.addBottom(GitShallowCloneComponentFactory.createShallowCloneRow(myShallowCloneViewModel));

        if (GitVcsApplicationSettings.getInstance().getSshExecutableType() != GitVcsApplicationSettings.SshExecutable.PUTTY) {
            return;
        }

        FileChooserTextBoxBuilder puttyKeyBuilder = FileChooserTextBoxBuilder.create(myProject);
        puttyKeyBuilder.uiDisposable(myUiDisposable);
        puttyKeyBuilder.fileChooserDescriptor(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor());

        FileChooserTextBoxBuilder.Controller puttyKeyController = puttyKeyBuilder.build();
        puttyKeyController.setValue(StringUtil.notNullize(GitRememberedInputs.getInstance().getPuttyKey()));
        myPuttyKeyController = puttyKeyController;

        builder.addLabeled(GitLocalize.cloneRepositoryPuttyKey(), puttyKeyController.getComponent());
    }

    @Override
    protected void rememberCustomSettings() {
        GitRememberedInputs.getInstance().setPuttyKey(getPuttyKeyFile());
    }

    @Override
    @RequiredUIAccess
    protected void test(String url, Consumer<Boolean> result) {
        GitLineHandlerPasswordRequestAware handler =
            new GitLineHandlerPasswordRequestAware(myProject, new File("."), GitCommand.LS_REMOTE);
        handler.setPuttyKey(getPuttyKeyFile());
        handler.setUrl(url);
        handler.addParameters(url, "master");

        GitTask task = new GitTask(myProject, handler, GitLocalize.cloneTesting(url));
        task.executeAsync(new GitTaskResultHandlerAdapter() {
            @Override
            protected void run(GitTaskResult taskResult) {
                result.accept(taskResult.isOK() || handler.hadAuthRequest());
            }
        });
    }

    @Override
    @RequiredUIAccess
    protected void doClone(CheckoutCallback callback, String sourceRepositoryUrl, String parentDirectory, String directoryName) {
        GitCheckoutProvider.clone(
            myProject,
            myGit,
            callback,
            sourceRepositoryUrl,
            directoryName,
            parentDirectory,
            getPuttyKeyFile(),
            myShallowCloneViewModel.getShallowCloneOptions()
        );
    }

    @RequiredUIAccess
    private @Nullable String getPuttyKeyFile() {
        return myPuttyKeyController == null ? null : StringUtil.nullize(myPuttyKeyController.getValue());
    }
}
