/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
import consulo.language.editor.ui.EditorBox;
import consulo.language.editor.ui.EditorBoxBuilderFactory;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.ComboBox;
import consulo.ui.Hyperlink;
import consulo.ui.Label;
import consulo.ui.Length;
import consulo.ui.MessageBoxBuilder;
import consulo.ui.MessageButtonRole;
import consulo.ui.Space;
import consulo.ui.TextAttribute;
import consulo.ui.TextItemPresentation;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.SimpleTextAttributes;
import consulo.ui.ex.util.TextAttributeUtil;
import consulo.ui.layout.HorizontalLayout;
import consulo.util.concurrent.coroutine.Coroutine;
import consulo.util.concurrent.coroutine.step.CodeExecution;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.distributed.push.PushTargetCompletionProvider;
import consulo.versionControlSystem.distributed.push.PushTargetEditorListener;
import consulo.versionControlSystem.distributed.push.PushTargetPanel;
import git4idea.GitRemoteBranch;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.text.ParseException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class GitPushTargetPanel extends PushTargetPanel<GitPushTarget> {
    private static final Logger LOG = Logger.getInstance(GitPushTargetPanel.class);

    private static final Comparator<GitRemoteBranch> REMOTE_BRANCH_COMPARATOR = new MyRemoteBranchComparator();
    private static final String SEPARATOR = " : ";

    @Nonnull
    private final GitPushSupport myPushSupport;
    @Nonnull
    private final GitRepository myRepository;
    @Nonnull
    private final Git myGit;
    @Nonnull
    private final Project myProject;
    @Nonnull
    private final List<PushTargetEditorListener> myListeners = new CopyOnWriteArrayList<>();

    @Nullable
    private GitPushTarget myCurrentTarget;
    @Nonnull
    private LocalizeValue myError = LocalizeValue.empty();
    @Nullable
    private Runnable myFireOnChangeAction;

    @Nonnull
    private String myRemoteName = "";
    @Nonnull
    private String myTargetName = "";
    @Nonnull
    private String myEditedTargetName = "";
    private boolean myEditing;

    @Nullable
    private HorizontalLayout myComponent;
    @Nullable
    private ComboBox<String> myRemoteSelector;
    @Nullable
    private Hyperlink myDefineRemoteLink;
    @Nullable
    private EditorBox myTargetEditor;

    public GitPushTargetPanel(@Nonnull GitPushSupport support, @Nonnull GitRepository repository, @Nullable GitPushTarget defaultTarget) {
        myPushSupport = support;
        myRepository = repository;
        myGit = Git.getInstance();
        myProject = myRepository.getProject();

        updateModel(defaultTarget);
    }

    private void updateModel(@Nullable GitPushTarget target) {
        myCurrentTarget = target;
        myError = LocalizeValue.empty();

        String initialBranch = "";
        String initialRemote = "";
        boolean noRemotes = myRepository.getRemotes().isEmpty();
        if (target == null) {
            if (myRepository.getCurrentBranch() == null) {
                myError = LocalizeValue.localizeTODO("Detached HEAD");
            }
            else if (myRepository.isFresh()) {
                myError = LocalizeValue.localizeTODO("Empty repository");
            }
            else if (!noRemotes) {
                myError = LocalizeValue.localizeTODO("Can't push");
            }
        }
        else {
            initialBranch = getTextFieldText(target);
            initialRemote = target.remoteBranch().getRemote().getName();
        }

        myTargetName = initialBranch;
        myEditedTargetName = initialBranch;
        myRemoteName = initialRemote;
    }

    @Nonnull
    @RequiredUIAccess
    @Override
    public HorizontalLayout getComponent() {
        HorizontalLayout component = myComponent;
        if (component == null) {
            component = HorizontalLayout.create(Space.SMALL);
            myComponent = component;
            rebuildComponent();
        }
        return component;
    }

    @RequiredUIAccess
    private void rebuildComponent() {
        HorizontalLayout component = myComponent;
        if (component == null) {
            return;
        }
        component.removeAll();
        myRemoteSelector = null;
        myDefineRemoteLink = null;

        List<String> remotes = getRemotes();
        if (remotes.isEmpty()) {
            Hyperlink defineRemote = Hyperlink.create(LocalizeValue.localizeTODO("Define remote"), event -> showDefineRemoteDialog());
            myDefineRemoteLink = defineRemote;
            component.add(defineRemote);
            return;
        }

        if (remotes.size() > 1) {
            ComboBox<String> remoteSelector = ComboBox.create(remotes);
            remoteSelector.setValue(myRemoteName);
            remoteSelector.addValueListener(event -> {
                String value = event.getValue();
                if (value != null) {
                    myRemoteName = value;
                }
            });
            myRemoteSelector = remoteSelector;
            component.add(remoteSelector);
        }
        else {
            component.add(Label.create(LocalizeValue.of(myRemoteName)));
        }
        component.add(Label.create(LocalizeValue.of(SEPARATOR.trim())));
        component.add(getTargetEditor());
    }

    @Nonnull
    @RequiredUIAccess
    private EditorBox getTargetEditor() {
        EditorBox targetEditor = myTargetEditor;
        if (targetEditor == null) {
            targetEditor = myProject.getApplication().getInstance(EditorBoxBuilderFactory.class)
                .create(myProject)
                .text(myEditedTargetName)
                .completion(new PushTargetCompletionProvider(getTargetNames(myRepository)))
                .build();
            targetEditor.setWidth(Length.ofFont(20));
            targetEditor.addValueListener(event -> {
                String value = event.getValue();
                myEditedTargetName = value == null ? "" : value;
                if (myEditing) {
                    for (PushTargetEditorListener listener : myListeners) {
                        listener.onTargetInEditModeChanged(myEditedTargetName);
                    }
                }
            });
            myTargetEditor = targetEditor;
        }
        return targetEditor;
    }

    @RequiredUIAccess
    @Override
    public void editingStarted() {
        myEditing = true;

        ComboBox<String> remoteSelector = myRemoteSelector;
        if (remoteSelector != null) {
            remoteSelector.setValue(myRemoteName);
        }

        EditorBox targetEditor = myTargetEditor;
        if (targetEditor != null && myDefineRemoteLink == null) {
            targetEditor.setValue(myEditedTargetName, false);
            targetEditor.focus();
        }
    }

    @Override
    public boolean isEditable() {
        return myCurrentTarget != null || (myError.isEmpty() && myRepository.getRemotes().isEmpty());
    }

    @RequiredUIAccess
    private void showDefineRemoteDialog() {
        GitDefineRemoteDialog.show(myRepository, myGit).whenComplete((remote, error) -> {
            if (remote != null) {
                addRemoteUnderModal(remote.name(), remote.url());
            }
        });
    }

    @RequiredUIAccess
    private void addRemoteUnderModal(@Nonnull String remoteName, @Nonnull String remoteUrl) {
        UIAccess uiAccess = UIAccess.current();
        myProject.getApplication().getInstance(ProgressBuilderFactory.class)
            .newProgressBuilder(myProject, LocalizeValue.localizeTODO("Adding remote..."))
            .modal()
            .cancelable()
            .execute(uiAccess, () -> Coroutine.first(CodeExecution.<Object, GitCommandResult>apply((input, continuation) -> {
                GitCommandResult result = myGit.addRemote(myRepository, remoteName, remoteUrl);
                myRepository.update();
                return result;
            })))
            .whenCompleteAsync((result, error) -> {
                if (error != null) {
                    LOG.warn(error);
                    return;
                }

                if (result.success()) {
                    updateModel(myPushSupport.getDefaultTarget(myRepository));
                    rebuildComponent();
                    Runnable fireOnChangeAction = myFireOnChangeAction;
                    if (fireOnChangeAction != null) {
                        fireOnChangeAction.run();
                    }
                }
                else {
                    String message = "Couldn't add remote: " + result.getErrorOutputAsHtmlString();
                    LOG.warn(message);
                    MessageBoxBuilder.<Boolean>create()
                        .asError()
                        .title(LocalizeValue.localizeTODO("Add Remote"))
                        .text(LocalizeValue.of(message))
                        .button(MessageButtonRole.OK, Boolean.TRUE)
                        .asDefaultButton()
                        .showAsync();
                }
            }, uiAccess);
    }

    @Nonnull
    private List<String> getRemotes() {
        return ContainerUtil.map(myRepository.getRemotes(), GitRemote::getName);
    }

    @Override
    public void render(
        @Nonnull TextItemPresentation presentation,
        boolean isSelected,
        boolean isActive,
        @Nullable String forceRenderedText
    ) {
        TextAttribute targetTextAttributes = attributes(SimpleTextAttributes.REGULAR_ATTRIBUTES, isActive);
        if (myError.isNotEmpty()) {
            presentation.append(myError, attributes(SimpleTextAttributes.ERROR_ATTRIBUTES, isActive));
            return;
        }

        List<String> remotes = getRemotes();
        if (remotes.isEmpty()) {
            presentation.append(LocalizeValue.localizeTODO("Define remote"), attributes(SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, true));
            return;
        }

        if (remotes.size() > 1) {
            presentation.append(myRemoteName, attributes(SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, isActive));
        }
        else {
            presentation.append(myRemoteName, targetTextAttributes);
        }
        presentation.append(SEPARATOR, targetTextAttributes);
        if (forceRenderedText != null) {
            presentation.append(forceRenderedText);
            return;
        }

        GitPushTarget target = getValue();
        if (target != null && target.isNewBranchCreated()) {
            presentation.append("+", attributes(SimpleTextAttributes.SYNTHETIC_ATTRIBUTES, isActive));
        }
        presentation.append(myTargetName, attributes(SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, isActive));
    }

    @Nonnull
    private static TextAttribute attributes(@Nonnull SimpleTextAttributes attributes, boolean isActive) {
        return TextAttributeUtil.toTextAttribute(isActive ? attributes : SimpleTextAttributes.GRAYED_ATTRIBUTES);
    }

    @Nullable
    @Override
    public GitPushTarget getValue() {
        return myCurrentTarget;
    }

    @Nonnull
    private static String getTextFieldText(@Nullable GitPushTarget target) {
        return (target != null ? target.remoteBranch().getNameForRemoteOperations() : "");
    }

    @Override
    @RequiredUIAccess
    public void fireOnCancel() {
        myEditing = false;
        myEditedTargetName = getTextFieldText(myCurrentTarget);
        if (myCurrentTarget != null) {
            myRemoteName = myCurrentTarget.remoteBranch().getRemote().getName();
        }

        EditorBox targetEditor = myTargetEditor;
        if (targetEditor != null) {
            targetEditor.setValue(myEditedTargetName, false);
        }
    }

    @Override
    @RequiredUIAccess
    public void fireOnChange() {
        myEditing = false;
        //any changes are senselessly if no remotes
        if (myError.isNotEmpty() || myRepository.getRemotes().isEmpty()) {
            return;
        }
        String remoteName = myRemoteName;
        String branchName = myEditedTargetName;
        try {
            myCurrentTarget = GitPushTarget.parse(myRepository, remoteName, branchName);
            myTargetName = branchName;
        }
        catch (ParseException e) {
            LOG.error("Invalid remote name shouldn't be allowed. [" + remoteName + ", " + branchName + "]", e);
        }
    }

    @Nonnull
    @Override
    @RequiredUIAccess
    public LocalizeValue verify() {
        if (myError.isNotEmpty()) {
            return myError;
        }
        if (myRepository.getRemotes().isEmpty()) {
            return LocalizeValue.localizeTODO("No remotes defined");
        }
        try {
            GitPushTarget.parse(myRepository, myRemoteName, myEditedTargetName);
            return LocalizeValue.empty();
        }
        catch (ParseException e) {
            return LocalizeValue.of(e.getMessage());
        }
    }

    @Override
    public void setFireOnChangeAction(@Nonnull Runnable action) {
        myFireOnChangeAction = action;
    }

    @Nonnull
    private static List<String> getTargetNames(@Nonnull GitRepository repository) {
        List<GitRemoteBranch> remoteBranches = ContainerUtil.sorted(repository.getBranches().getRemoteBranches(), REMOTE_BRANCH_COMPARATOR);
        return ContainerUtil.map(remoteBranches, GitRemoteBranch::getNameForRemoteOperations);
    }

    private static class MyRemoteBranchComparator implements Comparator<GitRemoteBranch> {
        @Override
        public int compare(@Nonnull GitRemoteBranch o1, @Nonnull GitRemoteBranch o2) {
            String remoteName1 = o1.getRemote().getName();
            String remoteName2 = o2.getRemote().getName();
            int remoteComparison = remoteName1.compareTo(remoteName2);
            if (remoteComparison != 0) {
                if (remoteName1.equals(GitRemote.ORIGIN_NAME)) {
                    return -1;
                }
                if (remoteName2.equals(GitRemote.ORIGIN_NAME)) {
                    return 1;
                }
                return remoteComparison;
            }
            return o1.getNameForLocalOperations().compareTo(o2.getNameForLocalOperations());
        }
    }

    @Override
    public void addTargetEditorListener(@Nonnull PushTargetEditorListener listener) {
        myListeners.add(listener);
    }

    @Override
    @RequiredUIAccess
    public void forceUpdateEditableUiModel(@Nonnull String forcedText) {
        //if targetEditor is now editing by user, it shouldn't be force updated
        if (!myEditing) {
            myEditedTargetName = forcedText;
            EditorBox targetEditor = myTargetEditor;
            if (targetEditor != null) {
                targetEditor.setValue(forcedText, false);
            }
        }
    }
}
