// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.ui;

import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.ui.CheckBox;
import consulo.ui.Component;
import consulo.ui.IntBox;
import consulo.ui.Label;
import consulo.ui.Space;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.layout.HorizontalLayout;

public final class GitShallowCloneComponentFactory {
    private static final String GIT_CLONE_DEPTH_ARG = "--depth";

    @RequiredUIAccess
    public static Component createShallowCloneRow(GitShallowCloneViewModel vm) {
        CheckBox shallowCloneCheckbox = CheckBox.create(GitLocalize.cloneDialogShallowClone());
        shallowCloneCheckbox.setValue(vm.isShallowClone());

        IntBox depthField = IntBox.create(vm.getDepth());
        depthField.setRange(1, Integer.MAX_VALUE);
        depthField.setToolTipText(LocalizeValue.of(GIT_CLONE_DEPTH_ARG));
        depthField.setEnabled(vm.isShallowClone());

        shallowCloneCheckbox.addValueListener(event -> {
            vm.setShallowClone(event.getValue());
            depthField.setEnabled(event.getValue());
        });
        depthField.addValueListener(event -> {
            Integer depth = event.getValue();
            vm.setDepth(depth == null ? 1 : depth);
        });

        HorizontalLayout row = HorizontalLayout.create(Space.SMALL);
        row.add(shallowCloneCheckbox);
        row.add(depthField);
        row.add(Label.create(GitLocalize.cloneDialogShallowCloneDepth()));
        return row;
    }

    private GitShallowCloneComponentFactory() {
    }
}
