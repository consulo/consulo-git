// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.config;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.util.AtomicNotNullLazyValue;
import consulo.application.Application;
import consulo.project.Project;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import jakarta.annotation.Nonnull;
import consulo.platform.Platform;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for "current git executable".
 * Allows to get a path to git executable.
 */
//TODO: move git version related stuff here
@Singleton
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
public class GitExecutableManager {
    public static GitExecutableManager getInstance() {
        return Application.get().getInstance(GitExecutableManager.class);
    }

    @Nonnull
    private final GitVcsApplicationSettings myApplicationSettings;
    @Nonnull
    private final AtomicNotNullLazyValue<String> myDetectedExecutable;
    // platform id -> git found there
    private final Map<String, String> myDetectedOnPlatforms = new ConcurrentHashMap<>();

    @Inject
    public GitExecutableManager(@Nonnull GitVcsApplicationSettings applicationSettings) {
        myApplicationSettings = applicationSettings;
        myDetectedExecutable = AtomicNotNullLazyValue.createValue(new GitExecutableDetector()::detect);
    }

    @Nonnull
    public String getPathToGit() {
        String path = myApplicationSettings.getSavedPathToGit();
        return path == null ? getDetectedExecutable() : path;
    }

    /**
     * The git of the machine of the project - for a remote project the one of its platform: set in the project settings, or found
     * in the {@code PATH} there.
     */
    @Nonnull
    public String getPathToGit(@Nonnull Project project) {
        String path = GitVcsSettings.getInstance(project).getPathToGit();
        if (path != null) {
            return path;
        }
        Platform platform = project.getPlatform();
        if (platform == Platform.current()) {
            return getPathToGit();
        }
        String detected = myDetectedOnPlatforms.get(platform.getId());
        if (detected != null) {
            return detected;
        }
        Path git = platform.findExecutableInPath("git");
        if (git == null) {
            // not cached - it may be installed later
            return "git";
        }
        myDetectedOnPlatforms.put(platform.getId(), git.toString());
        return git.toString();
    }

    @Nonnull
    public String getDetectedExecutable() {
        return myDetectedExecutable.getValue();
    }
}
