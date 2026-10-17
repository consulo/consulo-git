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
package git4idea.util;

import consulo.disposer.Disposable;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awtUnsafe.TargetAWT;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.action.VcsContextFactory;
import consulo.versionControlSystem.ui.VcsChangesDialogFactory;
import consulo.versionControlSystem.ui.VcsFileList;
import jakarta.annotation.Nonnull;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Collection;
import java.util.List;

public class GitSimplePathsBrowser extends JPanel {
    @RequiredUIAccess
    public GitSimplePathsBrowser(@Nonnull Project project, @Nonnull Collection<String> absolutePaths, @Nonnull Disposable parentDisposable) {
        super(new BorderLayout());

        VcsFileList<FilePath> list = project.getApplication().getInstance(VcsChangesDialogFactory.class)
            .filePathList(project, toFilePaths(absolutePaths))
            .toolbar()
            .build(parentDisposable);

        add(TargetAWT.to(list.getComponent()));
    }

    @Nonnull
    private static List<FilePath> toFilePaths(@Nonnull Collection<String> absolutePaths) {
        VcsContextFactory vcsContextFactory = VcsContextFactory.getInstance();
        return ContainerUtil.map(absolutePaths, path -> vcsContextFactory.createFilePathOn(new File(path), false));
    }
}
