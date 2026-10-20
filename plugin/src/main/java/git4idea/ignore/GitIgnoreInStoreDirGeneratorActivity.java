// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ignore;

import consulo.annotation.component.ExtensionImpl;
import consulo.project.Project;
import consulo.project.ProjectCoreUtil;
import consulo.project.startup.BackgroundStartupActivity;
import consulo.ui.UIAccess;
import consulo.versionControlSystem.ProjectLevelVcsManager;

@ExtensionImpl
public class GitIgnoreInStoreDirGeneratorActivity implements BackgroundStartupActivity {
    @Override
    public void runActivity(Project project, UIAccess uiAccess) {
        if (!ProjectCoreUtil.isDirectoryBased(project)) {
            return;
        }

        ProjectLevelVcsManager.getInstance(project).runAfterInitialization(
            () -> project.getApplication().executeOnPooledThread(() -> GitIgnoreInStoreDirGenerator.getInstance(project).run())
        );
    }
}
