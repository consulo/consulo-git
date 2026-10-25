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

import consulo.disposer.Disposable;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.Platform;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.ui.Component;
import consulo.ui.Hyperlink;
import consulo.ui.ImageBox;
import consulo.ui.Label;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.dialog.DialogDescriptor;
import consulo.ui.ex.dialog.action.DialogOkAction;
import consulo.ui.layout.DockLayout;
import consulo.ui.layout.VerticalLayout;

import java.util.List;

public class ShareProjectExistingRemotesDialog extends DialogDescriptor {
    private final String myHostServiceName;
    private final List<String> myRemotes;

    public ShareProjectExistingRemotesDialog(String hostServiceName, List<String> remotes) {
        super(GitLocalize.shareErrorProjectIsOnHost(hostServiceName));
        myHostServiceName = hostServiceName;
        myRemotes = remotes;
    }

    @RequiredUIAccess
    @Override
    public Component createCenterComponent(Disposable uiDisposable) {
        Label mainText = Label.create(myRemotes.size() == 1
            ? GitLocalize.shareActionRemoteIsOnHost(myHostServiceName)
            : GitLocalize.shareActionRemotesAreOnHost(myHostServiceName));

        VerticalLayout remotesPanel = VerticalLayout.create();
        for (String remote : myRemotes) {
            remotesPanel.add(Hyperlink.create(LocalizeValue.of(remote), event -> Platform.current().openInBrowser(remote)));
        }

        DockLayout messagesPanel = DockLayout.create();
        messagesPanel.top(mainText);
        messagesPanel.center(remotesPanel);

        DockLayout iconContainer = DockLayout.create();
        iconContainer.top(ImageBox.create(PlatformIconGroup.generalQuestiondialog()));

        DockLayout root = DockLayout.create();
        root.left(iconContainer);
        root.center(messagesPanel);
        return root;
    }

    @Override
    protected DialogOkAction createOkAction() {
        return new DialogOkAction(GitLocalize.shareAnywayButton());
    }
}
