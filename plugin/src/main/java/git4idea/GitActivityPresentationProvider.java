// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea;

import consulo.annotation.component.ExtensionImpl;
import consulo.localHistory.ActivityPresentationProvider;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.ui.image.Image;
import consulo.versionControlSystem.distributed.icon.DistributedVersionControlIconGroup;
import org.jspecify.annotations.Nullable;

@ExtensionImpl
public class GitActivityPresentationProvider implements ActivityPresentationProvider {
    public static final String ID = "Git";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public @Nullable Image getIcon(String kind) {
        if (kind.equals(GitActivity.Merge.kind())) {
            return PlatformIconGroup.vcsMerge();
        }
        if (kind.equals(GitActivity.Stash.kind())) {
            return PlatformIconGroup.vcsStash();
        }
        if (kind.equals(GitActivity.Unstash.kind())) {
            return PlatformIconGroup.vcsUnshelve();
        }
        if (kind.equals(GitActivity.Reset.kind()) || kind.equals(GitActivity.Revert.kind())) {
            return PlatformIconGroup.actionsRollback();
        }
        if (kind.equals(GitActivity.CherryPick.kind())) {
            return DistributedVersionControlIconGroup.cherrypick();
        }
        return null;
    }
}
