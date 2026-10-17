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

import consulo.localize.LocalizeValue;
import consulo.ui.CheckBox;
import consulo.ui.ComboBox;
import consulo.ui.Component;
import consulo.ui.Space;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.layout.HorizontalLayout;
import consulo.versionControlSystem.distributed.push.VcsPushOptionValue;
import consulo.versionControlSystem.distributed.push.VcsPushOptionsPanel;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public class GitPushOptionsPanel extends VcsPushOptionsPanel {
    @Nonnull
    private final CheckBox myPushTags;
    @Nonnull
    private final ComboBox<GitPushTagMode> myPushTagsMode;
    @Nonnull
    private final CheckBox myRunHooks;
    private final boolean myFollowTagsSupported;
    private final boolean myShowSkipHookOption;
    @Nonnull
    private final HorizontalLayout myComponent = HorizontalLayout.create(Space.LARGE);

    @RequiredUIAccess
    public GitPushOptionsPanel(@Nullable GitPushTagMode defaultMode, boolean followTagsSupported, boolean showSkipHookOption) {
        myFollowTagsSupported = followTagsSupported;
        myShowSkipHookOption = showSkipHookOption;

        myPushTags = CheckBox.create(LocalizeValue.localizeTODO(followTagsSupported ? "Push _Tags:" : "Push _Tags"));
        myPushTags.setValue(defaultMode != null);

        myPushTagsMode = ComboBox.create(GitPushTagMode.getValues());
        myPushTagsMode.setTextRenderer(mode -> mode == null ? LocalizeValue.empty() : LocalizeValue.of(mode.getTitle()));
        myPushTagsMode.setValue(defaultMode != null ? defaultMode : GitPushTagMode.ALL);
        myPushTagsMode.setEnabled(myPushTags.getValueOrError());

        myPushTags.addValueListener(event -> myPushTagsMode.setEnabled(event.getValue()));

        myRunHooks = CheckBox.create(LocalizeValue.localizeTODO("Run Git _hooks"));
        myRunHooks.setValue(true);

        HorizontalLayout tags = HorizontalLayout.create(Space.SMALL);
        tags.add(myPushTags);
        if (followTagsSupported) {
            tags.add(myPushTagsMode);
        }
        myComponent.add(tags);
        if (showSkipHookOption) {
            myComponent.add(myRunHooks);
        }
    }

    @Nonnull
    @Override
    public Component getComponent() {
        return myComponent;
    }

    @Nullable
    @Override
    public VcsPushOptionValue getValue() {
        GitPushTagMode selectedTagMode = !myFollowTagsSupported ? GitPushTagMode.ALL : myPushTagsMode.getValue();
        GitPushTagMode tagMode = myPushTags.getValueOrError() ? selectedTagMode : null;
        return new GitVcsPushOptionValue(tagMode, myShowSkipHookOption && !myRunHooks.getValueOrError());
    }
}
