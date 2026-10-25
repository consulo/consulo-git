// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.workingTrees.actions;

import consulo.application.util.UserHomeFileUtil;
import consulo.disposer.Disposable;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.Component;
import consulo.ui.ListBox;
import consulo.ui.TextAttribute;
import consulo.ui.WidthAndHeight;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.dialog.Dialog;
import consulo.ui.ex.dialog.DialogDescriptor;
import consulo.ui.ex.dialog.DialogService;
import consulo.ui.ex.dialog.DialogValue;
import consulo.ui.image.Image;
import consulo.ui.image.ImageEffects;
import consulo.ui.layout.ScrollableLayout;
import consulo.util.lang.StringUtil;
import git4idea.GitWorkingTree;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class GitChooseWorkingTreeDialog extends DialogDescriptor {
    private static final int MAX_PATH_LENGTH = 60;

    /**
     * {@code workingTrees}' only entry, or the one the user picks from a chooser dialog among several.
     * {@code null} when {@code workingTrees} is empty, or when the user cancels the chooser.
     */
    @RequiredUIAccess
    public static CompletableFuture<@Nullable GitWorkingTree> chooseAmong(Project project, List<GitWorkingTree> workingTrees) {
        if (workingTrees.size() == 1 || workingTrees.isEmpty()) {
            return CompletableFuture.completedFuture(workingTrees.isEmpty() ? null : workingTrees.getFirst());
        }
        GitChooseWorkingTreeDialog descriptor = new GitChooseWorkingTreeDialog(workingTrees);
        Dialog dialog = project.getApplication().getInstance(DialogService.class).build(descriptor);
        descriptor.myDialog = dialog;
        return dialog.showAsync().handle((value, error) -> error == null && value != null ? descriptor.getSelectedWorkingTree() : null);
    }

    private final List<GitWorkingTree> myWorkingTrees;
    private @Nullable ListBox<GitWorkingTree> myList;
    private @Nullable Dialog myDialog;

    private GitChooseWorkingTreeDialog(List<GitWorkingTree> workingTrees) {
        super(GitLocalize.actionOpenWorktreeForABranchChooseTitle());
        myWorkingTrees = workingTrees;
    }

    public @Nullable GitWorkingTree getSelectedWorkingTree() {
        ListBox<GitWorkingTree> list = myList;
        return list == null ? null : list.getValue();
    }

    @Override
    public WidthAndHeight getInitialSize() {
        return WidthAndHeight.ofPixel(520, 260);
    }

    @RequiredUIAccess
    @Override
    public Component createCenterComponent(Disposable uiDisposable) {
        ListBox<GitWorkingTree> list = ListBox.create(myWorkingTrees);
        list.setRenderer((presentation, item) -> {
            GitWorkingTree value = item.getValue();
            Image icon = value.isCurrent() ? PlatformIconGroup.actionsChecked() : ImageEffects.empty(Image.DEFAULT_ICON_SIZE);
            presentation.withIcon(icon);
            presentation.append(value.getPath().getName());
            presentation.append(LocalizeValue.of("  " + StringUtil.trimMiddle(
                UserHomeFileUtil.getLocationRelativeToUserHome(value.getPath().getPath()), MAX_PATH_LENGTH
            )), TextAttribute.GRAYED);
        });
        list.setValueByIndex(0);
        list.addValueListener(event -> updateOkButtonState());
        list.addDoubleClickListener(event -> {
            Dialog dialog = myDialog;
            if (dialog != null && list.getValue() != null) {
                dialog.doOkAction(DialogValue.OK_VALUE);
            }
        });
        myList = list;
        return ScrollableLayout.create(list);
    }

    @Override
    public boolean doUpdateOkButtonState() {
        return getSelectedWorkingTree() != null;
    }

    @Override
    public @Nullable Component getPreferredFocusedComponent() {
        return myList;
    }
}
