// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.config;

import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;

public enum GitSaveChangesPolicy {
    STASH {
        @Override
        public LocalizeValue getText() {
            return GitLocalize.localChangesSavePolicyStash();
        }

        @Override
        public LocalizeValue selectBundleMessage(LocalizeValue stashMessage, LocalizeValue shelfMessage) {
            return stashMessage;
        }
    },
    SHELVE {
        @Override
        public LocalizeValue getText() {
            return GitLocalize.localChangesSavePolicyShelve();
        }

        @Override
        public LocalizeValue selectBundleMessage(LocalizeValue stashMessage, LocalizeValue shelfMessage) {
            return shelfMessage;
        }
    };

    public abstract LocalizeValue getText();

    public abstract LocalizeValue selectBundleMessage(LocalizeValue stashMessage, LocalizeValue shelfMessage);
}
