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

import consulo.container.boot.ContainerPathManager;
import consulo.disposer.Disposable;
import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.fileChooser.FileChooserTextBoxBuilder;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.Alerts;
import consulo.ui.Button;
import consulo.ui.Component;
import consulo.ui.Label;
import consulo.ui.TextBox;
import consulo.ui.TextBoxWithHistory;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.layout.DockLayout;
import consulo.ui.style.StandardColors;
import consulo.ui.util.FormBuilder;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.checkout.CheckoutCallback;
import consulo.versionControlSystem.checkout.CheckoutPage;
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
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * @author VISTALL
 * @since 2026-08-25
 */
public class GitCheckoutPage implements CheckoutPage {
    private static final Pattern SSH_URL_PATTERN;

    static {
        String ch = "[\\p{ASCII}&&[\\p{Graph}]&&[^@:/]]";
        String host = ch + "+(?:\\." + ch + "+)*";
        String path = "/?" + ch + "+(?:/" + ch + "+)*/?";
        String all = "(?:" + ch + "+@)?" + host + ":" + path;
        SSH_URL_PATTERN = Pattern.compile(all);
    }

    private final Project myProject;
    private final Disposable myUiDisposable;
    private final Git myGit;

    private @Nullable TextBoxWithHistory myRepositoryUrlBox;
    private FileChooserTextBoxBuilder.@Nullable Controller myParentDirectoryController;
    private @Nullable TextBox myDirectoryNameBox;
    private FileChooserTextBoxBuilder.@Nullable Controller myPuttyKeyController;
    private @Nullable Label myErrorLabel;
    private @Nullable Button myTestButton;

    private String myDefaultDirectoryName = "";

    private @Nullable String myTestUrl;
    private @Nullable Boolean myTestResult;

    public GitCheckoutPage(Project project, Disposable uiDisposable, Git git) {
        myProject = project;
        myUiDisposable = uiDisposable;
        myGit = git;
    }

    @Override
    @RequiredUIAccess
    public Component createComponent(Context context) {
        GitRememberedInputs rememberedInputs = GitRememberedInputs.getInstance();

        TextBoxWithHistory repositoryUrlBox = TextBoxWithHistory.create();
        repositoryUrlBox.setHistory(rememberedInputs.getVisitedUrls());
        myRepositoryUrlBox = repositoryUrlBox;

        FileChooserTextBoxBuilder parentDirectoryBuilder = FileChooserTextBoxBuilder.create(myProject);
        parentDirectoryBuilder.uiDisposable(myUiDisposable);
        parentDirectoryBuilder.fileChooserDescriptor(FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withShowFileSystemRoots(true)
            .withHideIgnored(false));
        parentDirectoryBuilder.dialogTitle(GitLocalize.cloneDestinationDirectoryTitle());
        parentDirectoryBuilder.dialogDescription(GitLocalize.cloneDestinationDirectoryDescription());

        FileChooserTextBoxBuilder.Controller parentDirectoryController = parentDirectoryBuilder.build();
        parentDirectoryController.setValue(defaultParentDirectory(rememberedInputs));
        myParentDirectoryController = parentDirectoryController;

        TextBox directoryNameBox = TextBox.create();
        myDirectoryNameBox = directoryNameBox;

        Label errorLabel = Label.create();
        errorLabel.setForegroundColor(StandardColors.RED);
        myErrorLabel = errorLabel;

        Button testButton = Button.create(GitLocalize.cloneTest(), event -> runTest(context));
        testButton.setEnabled(false);
        myTestButton = testButton;

        repositoryUrlBox.addValueListener(event -> {
            syncDirectoryName();
            testButton.setEnabled(!getSourceRepositoryUrl().isEmpty());
            validate(context);
        });
        parentDirectoryController.getComponent().addValueListener(event -> validate(context));
        directoryNameBox.addValueListener(event -> validate(context));

        DockLayout urlLayout = DockLayout.create();
        urlLayout.center(repositoryUrlBox);
        urlLayout.right(testButton);

        FormBuilder builder = FormBuilder.create();
        builder.addLabeled(GitLocalize.cloneRepositoryUrl(), urlLayout);
        builder.addLabeled(GitLocalize.cloneParentDir(), parentDirectoryController.getComponent());
        builder.addLabeled(GitLocalize.cloneDirName(), directoryNameBox);

        if (GitVcsApplicationSettings.getInstance().getSshExecutableType() == GitVcsApplicationSettings.SshExecutable.PUTTY) {
            FileChooserTextBoxBuilder puttyKeyBuilder = FileChooserTextBoxBuilder.create(myProject);
            puttyKeyBuilder.uiDisposable(myUiDisposable);
            puttyKeyBuilder.fileChooserDescriptor(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor());

            FileChooserTextBoxBuilder.Controller puttyKeyController = puttyKeyBuilder.build();
            puttyKeyController.setValue(StringUtil.notNullize(rememberedInputs.getPuttyKey()));
            myPuttyKeyController = puttyKeyController;

            builder.addLabeled(GitLocalize.cloneRepositoryPuttyKey(), puttyKeyController.getComponent());
        }

        builder.addBottom(errorLabel);

        validate(context);

        return builder.build();
    }

    private static String defaultParentDirectory(GitRememberedInputs rememberedInputs) {
        String parentDir = rememberedInputs.getCloneParentDir();
        if (StringUtil.isEmptyOrSpaces(parentDir)) {
            parentDir = ContainerPathManager.get().getDocumentsDir().getPath();
        }
        return parentDir;
    }

    @RequiredUIAccess
    private void syncDirectoryName() {
        TextBox directoryNameBox = myDirectoryNameBox;
        if (directoryNameBox == null) {
            return;
        }

        String currentName = StringUtil.notNullize(directoryNameBox.getValue());
        if (!myDefaultDirectoryName.equals(currentName) && !currentName.isEmpty()) {
            return;
        }

        myDefaultDirectoryName = defaultDirectoryName(getSourceRepositoryUrl());
        directoryNameBox.setValue(myDefaultDirectoryName);
    }

    @RequiredUIAccess
    private void runTest(Context context) {
        String url = getSourceRepositoryUrl();
        if (url.isEmpty()) {
            return;
        }

        Button testButton = myTestButton;
        if (testButton != null) {
            testButton.setEnabled(false);
        }

        GitLineHandlerPasswordRequestAware handler =
            new GitLineHandlerPasswordRequestAware(myProject, new File("."), GitCommand.LS_REMOTE);
        handler.setPuttyKey(getPuttyKeyFile());
        handler.setUrl(url);
        handler.addParameters(url, "master");

        GitTask task = new GitTask(myProject, handler, GitLocalize.cloneTesting(url));
        task.executeAsync(new GitTaskResultHandlerAdapter() {
            @Override
            protected void run(GitTaskResult result) {
                onTestFinished(context, url, result.isOK() || handler.hadAuthRequest());
            }
        });
    }

    @RequiredUIAccess
    private void onTestFinished(Context context, String url, boolean success) {
        myTestUrl = url;
        myTestResult = success;

        Button testButton = myTestButton;
        if (testButton != null) {
            testButton.setEnabled(!getSourceRepositoryUrl().isEmpty());
        }

        if (success) {
            Alerts.okInfo(GitLocalize.cloneTestSuccessMessage(url)).showAsync();
        }

        validate(context);
    }

    @RequiredUIAccess
    private void validate(Context context) {
        LocalizeValue error = findError();

        Label errorLabel = myErrorLabel;
        if (errorLabel != null) {
            errorLabel.setText(error);
        }

        context.setCheckoutEnabled(error == LocalizeValue.empty());
    }

    @RequiredUIAccess
    private LocalizeValue findError() {
        String url = getSourceRepositoryUrl();
        if (url.isEmpty()) {
            return LocalizeValue.empty();
        }

        if (myTestResult != null && url.equals(myTestUrl)) {
            if (!myTestResult) {
                return GitLocalize.cloneTestFailedError();
            }
        }
        else {
            LocalizeValue urlError = findUrlError(url);
            if (urlError != LocalizeValue.empty()) {
                return urlError;
            }
        }

        String parentDirectory = getParentDirectory();
        String directoryName = getDirectoryName();
        if (parentDirectory.isEmpty() || directoryName.isEmpty()) {
            return LocalizeValue.empty();
        }

        File file = new File(parentDirectory, directoryName);
        if (file.exists()) {
            return GitLocalize.cloneDestinationExistsError(file);
        }
        if (!file.getParentFile().exists()) {
            return GitLocalize.cloneParentMissingError(file.getParent());
        }
        return LocalizeValue.empty();
    }

    private static LocalizeValue findUrlError(String url) {
        try {
            if (new URI(url).isAbsolute()) {
                return LocalizeValue.empty();
            }
        }
        catch (URISyntaxException ignored) {
        }

        if (SSH_URL_PATTERN.matcher(url).matches()) {
            return LocalizeValue.empty();
        }

        File file = new File(url);
        if (file.exists()) {
            return file.isDirectory() ? LocalizeValue.empty() : GitLocalize.cloneUrlIsNotDirectoryError();
        }

        return GitLocalize.cloneInvalidUrl();
    }

    @RequiredUIAccess
    private String getSourceRepositoryUrl() {
        return myRepositoryUrlBox == null ? "" : StringUtil.notNullize(myRepositoryUrlBox.getValue()).trim();
    }

    @RequiredUIAccess
    private String getParentDirectory() {
        return myParentDirectoryController == null ? "" : myParentDirectoryController.getValue();
    }

    @RequiredUIAccess
    private String getDirectoryName() {
        return myDirectoryNameBox == null ? "" : StringUtil.notNullize(myDirectoryNameBox.getValue());
    }

    @RequiredUIAccess
    private @Nullable String getPuttyKeyFile() {
        return myPuttyKeyController == null ? null : StringUtil.nullize(myPuttyKeyController.getValue());
    }

    @Override
    @RequiredUIAccess
    public void doCheckout(CheckoutCallback callback) {
        String sourceRepositoryUrl = getSourceRepositoryUrl();
        String parentDirectory = getParentDirectory();
        String directoryName = getDirectoryName();
        String puttyKey = getPuttyKeyFile();

        GitRememberedInputs rememberedInputs = GitRememberedInputs.getInstance();
        rememberedInputs.addUrl(sourceRepositoryUrl);
        rememberedInputs.setCloneParentDir(parentDirectory);
        rememberedInputs.setPuttyKey(puttyKey);

        GitCheckoutProvider.clone(
            myProject,
            myGit,
            callback,
            sourceRepositoryUrl,
            directoryName,
            parentDirectory,
            puttyKey
        );
    }

    private static String defaultDirectoryName(String url) {
        String nonSystemName;
        if (url.endsWith("/" + GitUtil.DOT_GIT) || url.endsWith(File.separator + GitUtil.DOT_GIT)) {
            nonSystemName = url.substring(0, url.length() - 5);
        }
        else if (url.endsWith(GitUtil.DOT_GIT)) {
            nonSystemName = url.substring(0, url.length() - 4);
        }
        else {
            nonSystemName = url;
        }

        int i = nonSystemName.lastIndexOf('/');
        if (i == -1 && File.separatorChar != '/') {
            i = nonSystemName.lastIndexOf(File.separatorChar);
        }
        return i >= 0 ? nonSystemName.substring(i + 1) : "";
    }
}
