// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.ignore.lang;

import consulo.annotation.component.ExtensionImpl;
import consulo.language.Language;
import consulo.language.file.FileViewProvider;
import consulo.language.file.LanguageFileViewProviderFactory;
import consulo.language.psi.PsiManager;
import consulo.virtualFileSystem.VirtualFile;

@ExtensionImpl
public class GitExcludeViewProviderFactory implements LanguageFileViewProviderFactory {
    @Override
    public FileViewProvider createFileViewProvider(VirtualFile file, Language language, PsiManager manager, boolean eventSystemEnabled) {
        return new GitExcludeViewProvider(manager, file, eventSystemEnabled);
    }

    @Override
    public Language getLanguage() {
        return GitExcludeLanguage.INSTANCE;
    }
}
