// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.workingTrees;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.WriteAction;
import consulo.git.localize.GitLocalize;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandler;
import git4idea.index.GitIndexStatusUtil;
import git4idea.repo.GitRepository;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * File I/O for the New Worktree dialog's "Copy project config" section: creating and editing a repository's
 * {@code .worktreeinclude} file. Kept separate from {@link git4idea.workingTrees.dialog.GitWorktreeConfigCategoriesPanel},
 * which only builds and wires up the UI.
 */
@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitWorktreeIncludeFileService {
    public static final String WORKTREE_INCLUDE_FILE_NAME = ".worktreeinclude";

    private static final Logger LOG = Logger.getInstance(GitWorktreeIncludeFileService.class);

    /**
     * Marks the idea-settings block this file generates. {@link #hasIdeaSettingsSection} looks for this exact text,
     * so this must not be localized: a locale-dependent marker would make "does this file already have the
     * section" locale-dependent too, and could silently duplicate the section.
     */
    private static final String IDEA_SETTINGS_SECTION_MARKER = "# --- Consulo project settings, added automatically ---";

    /**
     * Name patterns for files that are typically local overrides, never meant to be committed. The list
     * includes {@link #WORKTREE_INCLUDE_FILE_NAME} itself. An untracked .worktreeinclude file then matches its own
     * pattern, so {@link #findWorktreeIncludeMatches} and the copy pipeline propagate it into a freshly created
     * worktree too.
     */
    private static final List<String> LOCAL_OVERRIDE_NAME_PATTERNS = List.of(
        WORKTREE_INCLUDE_FILE_NAME,
        ".env", ".env.local", ".env.*.local",
        "local.properties", "*.local.properties",
        "docker-compose.override.yml", "docker-compose.override.yaml",
        "*.local.yml", "*.local.yaml", "*.local.json", "*.local.xml",
        "secrets.yml", "secrets.yaml"
    );

    private static final List<String> IDEA_SETTINGS_NAME_PATTERNS = List.of(
        Project.DIRECTORY_STORE_FOLDER + "/*.xml",
        Project.DIRECTORY_STORE_FOLDER + "/runConfigurations/",
        Project.DIRECTORY_STORE_FOLDER + "/codeStyles/",
        Project.DIRECTORY_STORE_FOLDER + "/inspectionProfiles/",
        ".run/"
    );

    public static GitWorktreeIncludeFileService getInstance(Project project) {
        return project.getInstance(GitWorktreeIncludeFileService.class);
    }

    /**
     * Returns every path under {@code root} that matches a pattern in {@code worktreeIncludeFile}, tracked or not. This
     * reuses git's own gitignore-syntax engine instead of a hand-rolled matcher, so a negated pattern ({@code !foo})
     * behaves exactly like it does in a real {@code .gitignore}.
     */
    public static List<Path> findWorktreeIncludeMatches(Project project, VirtualFile root, Path worktreeIncludeFile) throws VcsException {
        if (!worktreeIncludeFile.equals(root.toNioPath().resolve(WORKTREE_INCLUDE_FILE_NAME))) {
            throw new IllegalArgumentException(
                "worktreeIncludeFile must be " + WORKTREE_INCLUDE_FILE_NAME + " directly under root, got " + worktreeIncludeFile
            );
        }

        GitLineHandler handler = new GitLineHandler(project, root, GitCommand.LS_FILES);
        handler.setSilent(true);
        handler.addParameters("-z", "--cached", "--others", "--ignored", "--exclude-from=" + WORKTREE_INCLUDE_FILE_NAME);
        handler.endOptions();

        String output = Git.getInstance().runCommand(handler).getOutputOrThrow();
        if (output.isBlank()) {
            return List.of();
        }

        Path rootPath = root.toNioPath();
        List<Path> result = new ArrayList<>();
        for (String entry : output.split(GitIndexStatusUtil.NUL)) {
            if (!entry.isEmpty()) {
                result.add(rootPath.resolve(entry));
            }
        }
        return result;
    }

    public static String generateWorktreeIncludeContent(boolean includeIdeaSettings) {
        String content = GitLocalize.workingTreeDialogWorktreeIncludeHeaderComment().get() + "\n" +
            String.join("\n", LOCAL_OVERRIDE_NAME_PATTERNS) + "\n";
        return includeIdeaSettings ? content + ideaSettingsSection() : content;
    }

    /**
     * Returns {@code true} when {@code content} already has the idea-settings block {@link #generateWorktreeIncludeContent} adds.
     */
    public static boolean hasIdeaSettingsSection(String content) {
        return content.contains(IDEA_SETTINGS_SECTION_MARKER);
    }

    /**
     * Appends the idea-settings block to {@code content}, unless {@link #hasIdeaSettingsSection} is already {@code true}.
     */
    public static String appendIdeaSettingsSection(String content) {
        if (hasIdeaSettingsSection(content)) {
            return content;
        }
        String separator = content.isEmpty() || content.endsWith("\n") ? "" : "\n";
        return content + separator + ideaSettingsSection();
    }

    private static String ideaSettingsSection() {
        return IDEA_SETTINGS_SECTION_MARKER + "\n" +
            GitLocalize.workingTreeDialogWorktreeIncludeIdeaSettingsComment().get() + "\n" +
            String.join("\n", IDEA_SETTINGS_NAME_PATTERNS) + "\n";
    }

    /**
     * Writes a new {@code .worktreeinclude} file with {@code content} under {@code repository}'s root. Returns {@code true} on success;
     * the file always lands at the fixed {@link #WORKTREE_INCLUDE_FILE_NAME} path under {@code repository}'s root, so the
     * caller already knows it.
     */
    public boolean writeWorktreeIncludeFile(GitRepository repository, String content) {
        VirtualFile root = repository.getRoot();
        String fileContent = GitLocalize.workingTreeDialogWorktreeIncludeCloseComment().get() + "\n" + content;
        try {
            WriteAction.<Void, IOException>compute(() -> {
                VirtualFile newFile = root.createChildData(root, WORKTREE_INCLUDE_FILE_NAME);
                VirtualFileUtil.saveText(newFile, fileContent);
                return null;
            });
            return true;
        }
        catch (IOException e) {
            LOG.warn("Failed to create " + WORKTREE_INCLUDE_FILE_NAME + " under " + root.getPath(), e);
            return false;
        }
    }

    /**
     * Appends the idea-settings block to {@code repository}'s existing {@code .worktreeinclude} file. Returns {@code true} on
     * success, and {@code false} both when the file does not exist and when the update fails.
     */
    public boolean addIdeaSettingsSection(GitRepository repository) {
        VirtualFile root = repository.getRoot();
        VirtualFile file = root.findChild(WORKTREE_INCLUDE_FILE_NAME);
        if (file == null) {
            return false;
        }
        try {
            String updatedContent = appendIdeaSettingsSection(VirtualFileUtil.loadText(file));
            WriteAction.<Void, IOException>compute(() -> {
                VirtualFileUtil.saveText(file, updatedContent);
                return null;
            });
            return true;
        }
        catch (IOException e) {
            LOG.warn("Failed to update " + WORKTREE_INCLUDE_FILE_NAME + " under " + root.getPath(), e);
            return false;
        }
    }
}
