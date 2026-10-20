// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.ignore.lang;

import consulo.language.impl.file.SingleRootFileViewProvider;
import consulo.language.psi.PsiManager;
import consulo.virtualFileSystem.VirtualFile;

public class GitExcludeViewProvider extends SingleRootFileViewProvider {
    public GitExcludeViewProvider(PsiManager manager, VirtualFile virtualFile, boolean eventSystemEnabled) {
        super(manager, virtualFile, eventSystemEnabled, GitExcludeFileType.INSTANCE);
    }

    @Override
    protected boolean shouldCreatePsi() {
        return getBaseLanguage() instanceof GitExcludeLanguage;
    }
}
