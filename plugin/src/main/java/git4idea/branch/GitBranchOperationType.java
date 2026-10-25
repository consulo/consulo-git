// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.branch;

import consulo.git.localize.GitBranchesLocalize;
import consulo.localize.LocalizeValue;

public enum GitBranchOperationType {
    CREATE(GitBranchesLocalize.newBranchDialogOperationCreateName(), GitBranchesLocalize.newBranchDialogOperationCreateDescription()),
    CHECKOUT(GitBranchesLocalize.newBranchDialogOperationCheckoutName(), GitBranchesLocalize.newBranchDialogOperationCheckoutDescription()),
    RENAME(GitBranchesLocalize.newBranchDialogOperationRenameName(), LocalizeValue.empty());

    private final LocalizeValue myText;
    private final LocalizeValue myDescription;

    GitBranchOperationType(LocalizeValue text, LocalizeValue description) {
        myText = text;
        myDescription = description;
    }

    public LocalizeValue getText() {
        return myText;
    }

    public LocalizeValue getDescription() {
        return myDescription;
    }
}
