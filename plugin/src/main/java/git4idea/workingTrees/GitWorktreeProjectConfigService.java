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
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.jdom.JDOMUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.index.GitFileStatus;
import git4idea.index.GitIndexStatusUtil;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies local {@code .consulo}/{@code .run} configuration into a freshly created worktree, driven entirely by the source
 * repository's {@code .worktreeinclude} file.
 * <p>
 * A file counts as local only when git reports it as not yet part of a commit in the source worktree:
 * untracked, ignored, or staged but never committed. A file already present at the target path is always
 * left untouched.
 */
@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitWorktreeProjectConfigService {
    private static final Logger LOG = Logger.getInstance(GitWorktreeProjectConfigService.class);

    private static final String IDEA_DIR_NAME = Project.DIRECTORY_STORE_FOLDER;
    private static final String WORKSPACE_FILE_NAME = "workspace.xml";
    private static final String PROJECT_ID_COMPONENT_NAME = "ProjectId";

    public static GitWorktreeProjectConfigService getInstance(Project project) {
        return project.getInstance(GitWorktreeProjectConfigService.class);
    }

    /**
     * Removes the first {@code <component name="[name]">} from {@code root}, if present.
     */
    static void removeComponent(Element root, String name) {
        for (Element child : root.getChildren("component")) {
            if (name.equals(child.getAttributeValue("name"))) {
                root.removeContent(child);
                return;
            }
        }
    }

    /**
     * Removes the {@code ProjectId} component from {@code xmlText}, a {@code workspace.xml} file's content. Returns {@code null}
     * when the file has no {@code ProjectId} component, or fails to parse as XML, so the caller can tell "no change"
     * apart from "changed".
     */
    private static @Nullable String removeProjectIdComponent(String xmlText) {
        Element root;
        try {
            root = JDOMUtil.load(new StringReader(xmlText));
        }
        catch (JDOMException e) {
            LOG.warn("Failed to parse workspace.xml while stripping " + PROJECT_ID_COMPONENT_NAME, e);
            return null;
        }
        catch (IOException e) {
            LOG.warn("Failed to read workspace.xml while stripping " + PROJECT_ID_COMPONENT_NAME, e);
            return null;
        }
        if (root == null) {
            return null;
        }
        Element projectIdComponent = null;
        for (Element child : root.getChildren("component")) {
            if (PROJECT_ID_COMPONENT_NAME.equals(child.getAttributeValue("name"))) {
                projectIdComponent = child;
                break;
            }
        }
        if (projectIdComponent == null) {
            return null;
        }
        root.removeContent(projectIdComponent);
        StringWriter writer = new StringWriter();
        try {
            JDOMUtil.writeElement(root, writer, "\n");
        }
        catch (IOException e) {
            LOG.warn("Failed to write workspace.xml while stripping " + PROJECT_ID_COMPONENT_NAME, e);
            return null;
        }
        return writer.toString();
    }

    /**
     * Replaces every occurrence of {@code sourceRootString} in {@code text} with {@code targetRootString}. Returns {@code null} when
     * {@code text} has no occurrence, so the caller can tell "no change" apart from "changed".
     * <p>
     * Plain text, not XML-aware: a value already stored relative to {@code $PROJECT_DIR$} needs no change, since it
     * resolves correctly once the new project opens at the new root, so this only catches a literal absolute
     * path that leaked in without a macro, such as a working directory or an env-file path.
     * <p>
     * A boundary check keeps a match from firing on a path that merely starts with {@code sourceRootString}: a match
     * is replaced only when the character right after it could not continue the same file or directory name. A
     * JSON- or XML-escaped value can end a path in a quote, a comma, or another punctuation mark instead of a
     * path separator, so this checks for a name character instead of allow-listing {@code /} and {@code \}.
     */
    static @Nullable String remapAbsolutePaths(String text, String sourceRootString, String targetRootString) {
        if (sourceRootString.isEmpty() || sourceRootString.equals(targetRootString)) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        int searchFrom = 0;
        boolean changed = false;
        while (true) {
            int index = text.indexOf(sourceRootString, searchFrom);
            if (index < 0) {
                result.append(text, searchFrom, text.length());
                break;
            }
            int matchEnd = index + sourceRootString.length();
            result.append(text, searchFrom, index);
            if (matchEnd >= text.length() || !continuesPathName(text.charAt(matchEnd))) {
                result.append(targetRootString);
                changed = true;
            }
            else {
                result.append(sourceRootString);
            }
            searchFrom = matchEnd;
        }
        return changed ? result.toString() : null;
    }

    /**
     * {@code true} for a character that could continue the same file or directory name, so a match ending right
     * before it is not a real path boundary.
     */
    private static boolean continuesPathName(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == ' ';
    }

    /**
     * Copies {@code source} to {@code target}, following a symlink to its resolved content. Returns {@code false} on failure,
     * including a dangling symlink, instead of throwing.
     */
    static boolean copyConfigFile(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            return true;
        }
        catch (NoSuchFileException e) {
            LOG.warn("Failed to copy " + source + " to " + target + ", a dangling symlink or missing source", e);
            return false;
        }
        catch (IOException e) {
            LOG.warn("Failed to copy " + source + " to " + target, e);
            return false;
        }
    }

    private static @Nullable String readConfigFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path);
        }
        catch (IOException e) {
            LOG.warn("Failed to read " + path, e);
            return null;
        }
    }

    private static boolean writeConfigFile(String text, Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text);
            return true;
        }
        catch (IOException e) {
            LOG.warn("Failed to write " + path, e);
            return false;
        }
    }

    private final Project myProject;

    @Inject
    public GitWorktreeProjectConfigService(Project project) {
        myProject = project;
    }

    /**
     * Returns the subset of {@code existingFiles} that git reports as not yet part of a commit in {@code root}: untracked,
     * ignored, or staged but never committed. A staged-but-uncommitted file has no content in any commit yet, so
     * the new worktree would not have it either unless this service copies it.
     * <p>
     * The caller filters out a non-regular file first, so this never re-checks one it already ruled out. Throws
     * {@link VcsException} when it cannot read the git status, instead of returning an empty set a caller could
     * mistake for "nothing local to report".
     */
    private Set<Path> findLocalFiles(VirtualFile root, List<Path> existingFiles) throws VcsException {
        if (existingFiles.isEmpty()) {
            return Set.of();
        }

        List<FilePath> filePaths = new ArrayList<>(existingFiles.size());
        // Keyed by FilePath.getPath(), not Path.toString(): the latter uses '\' on Windows, while a status
        // result's path always uses '/', so keying by it would silently drop every match on Windows.
        Map<String, Path> filesByStatusPath = new HashMap<>();
        for (Path path : existingFiles) {
            FilePath filePath = VcsUtil.getFilePath(path.toString(), false);
            filePaths.add(filePath);
            filesByStatusPath.put(filePath.getPath(), path);
        }
        List<GitFileStatus> statuses = GitIndexStatusUtil.getStatus(myProject, root, filePaths, false, true, true, true);
        Set<Path> result = new LinkedHashSet<>();
        for (GitFileStatus status : statuses) {
            if (!status.isTracked() || GitIndexStatusUtil.isAdded(status.index())) {
                Path path = filesByStatusPath.get(status.path().getPath());
                if (path != null) {
                    result.add(path);
                }
            }
        }
        return result;
    }

    /**
     * Returns {@code true} when {@code sourceRoot}'s {@code workspace.xml} is already committed to git. Reuses
     * {@link #findLocalFiles} (git-status-based), not {@code git ls-files --cached}, since the latter cannot tell a
     * committed file apart from one that is staged but never committed.
     * <p>
     * Treats a failed git status read as "not committed", the safer default: it leaves the idea-settings
     * pattern offered rather than silently skipped.
     */
    public boolean isIdeaConfigCommittedToGit(VirtualFile sourceRoot) {
        Path workspaceXml = sourceRoot.toNioPath().resolve(IDEA_DIR_NAME).resolve(WORKSPACE_FILE_NAME);
        if (!Files.isRegularFile(workspaceXml)) {
            return false;
        }
        try {
            return findLocalFiles(sourceRoot, List.of(workspaceXml)).isEmpty();
        }
        catch (VcsException e) {
            LOG.warn("Failed to read the git status of " + workspaceXml, e);
            return false;
        }
    }

    /**
     * Copies every local file under {@code sourceRoot} that matches a pattern in its {@code .worktreeinclude} file, then
     * cleans up the freshly copied settings so the new worktree does not inherit a stale project identity
     * or an absolute path pointing back at {@code sourceRoot}. Returns the target paths this run failed to write.
     * <p>
     * Throws {@link VcsException} when it cannot read the {@code .worktreeinclude} matches or the git status of a match.
     * An empty result list already means "found nothing to copy", so swallowing either failure here would make
     * the caller believe nothing needed copying.
     */
    public List<Path> copyAndCleanUpWorktreeIncludeFiles(VirtualFile sourceRoot, Path targetWorktreeDir) throws VcsException {
        Path worktreeIncludeFile = sourceRoot.toNioPath().resolve(GitWorktreeIncludeFileService.WORKTREE_INCLUDE_FILE_NAME);
        if (!Files.isRegularFile(worktreeIncludeFile)) {
            return List.of();
        }

        List<Path> matches = GitWorktreeIncludeFileService.findWorktreeIncludeMatches(myProject, sourceRoot, worktreeIncludeFile);

        Path sourceRootPath = sourceRoot.toNioPath();
        Set<Path> localFiles = findLocalFiles(sourceRoot, matches.stream().filter(Files::isRegularFile).toList());
        List<Path> copiedFiles = new ArrayList<>();
        List<Path> failedFiles = new ArrayList<>();
        for (Path sourceFile : localFiles) {
            Path targetFile = targetWorktreeDir.resolve(sourceRootPath.relativize(sourceFile));
            if (Files.exists(targetFile)) {
                continue;
            }
            if (copyConfigFile(sourceFile, targetFile)) {
                copiedFiles.add(targetFile);
            }
            else {
                failedFiles.add(targetFile);
            }
        }
        List<Path> failedCleanup = cleanUpCopiedProjectFiles(copiedFiles, sourceRootPath, targetWorktreeDir);
        // Refreshed last, so the IDE sees cleanUpCopiedProjectFiles' rewritten content, not the raw copy.
        refreshCopiedFiles(copiedFiles);
        List<Path> result = new ArrayList<>(failedFiles);
        result.addAll(failedCleanup);
        return result;
    }

    /**
     * Forces the IDE to see every file in {@code copiedFiles} right away. They were written through raw NIO calls, so
     * without this the native file watcher has to work through the whole freshly checked-out worktree first,
     * and can take minutes to notice them.
     */
    private static void refreshCopiedFiles(List<Path> copiedFiles) {
        LocalFileSystem localFileSystem = LocalFileSystem.getInstance();
        for (Path path : copiedFiles) {
            VirtualFile virtualFile = localFileSystem.refreshAndFindFileByNioFile(path);
            if (virtualFile == null) {
                continue;
            }
            VirtualFileUtil.markDirtyAndRefresh(false, false, false, virtualFile);
        }
    }

    /**
     * Rewrites every freshly copied {@code .consulo}/{@code .run} XML or {@code .iml} file in {@code copiedFiles}: remaps an absolute path
     * still pointing at {@code sourceRootPath} to the same place under {@code targetWorktreeDir}, and, for
     * {@code workspace.xml} specifically, removes the {@code ProjectId} component. A newly created worktree needs its
     * own project identity; keeping the source's would make the IDE treat the two projects as one. Returns the
     * target paths this failed to write.
     */
    private static List<Path> cleanUpCopiedProjectFiles(List<Path> copiedFiles, Path sourceRootPath, Path targetWorktreeDir) {
        // Escaped for XML, since remapAbsolutePaths inserts this text as-is into an .xml or .iml file.
        String sourceRootString = StringUtil.escapeXmlEntities(sourceRootPath.toString());
        // Escaped for XML, since remapAbsolutePaths inserts this text as-is into an .xml or .iml file: an
        // unescaped '&' in the target path would otherwise leave the file unparsable.
        String targetRootString = StringUtil.escapeXmlEntities(targetWorktreeDir.toString());
        List<Path> failedFiles = new ArrayList<>();
        for (Path targetFile : copiedFiles) {
            if (!isCleanupCandidate(targetFile, targetWorktreeDir)) {
                continue;
            }
            String originalText = readConfigFile(targetFile);
            if (originalText == null) {
                continue;
            }

            String text = originalText;
            String remapped = remapAbsolutePaths(text, sourceRootString, targetRootString);
            if (remapped != null) {
                text = remapped;
            }
            if (isWorkspaceXml(targetFile, targetWorktreeDir)) {
                String withoutProjectId = removeProjectIdComponent(text);
                if (withoutProjectId != null) {
                    text = withoutProjectId;
                }
            }

            if (!text.equals(originalText) && !writeConfigFile(text, targetFile)) {
                failedFiles.add(targetFile);
            }
        }
        return failedFiles;
    }

    private static boolean isCleanupCandidate(Path targetFile, Path targetWorktreeDir) {
        String relative = targetWorktreeDir.relativize(targetFile).toString();
        String separator = targetFile.getFileSystem().getSeparator();
        boolean underIdeaOrRun = relative.startsWith(IDEA_DIR_NAME + separator) || relative.startsWith(".run" + separator);
        if (!underIdeaOrRun) {
            return false;
        }
        String fileName = targetFile.getFileName().toString();
        return fileName.endsWith(".xml") || fileName.endsWith(".iml");
    }

    private static boolean isWorkspaceXml(Path targetFile, Path targetWorktreeDir) {
        return targetFile.equals(targetWorktreeDir.resolve(IDEA_DIR_NAME).resolve(WORKSPACE_FILE_NAME));
    }
}
