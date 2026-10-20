// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ignore;

import consulo.annotation.component.ExtensionImpl;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.io.FileUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.NotIgnored;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsKey;
import consulo.versionControlSystem.action.VcsContextFactory;
import consulo.versionControlSystem.change.IgnoredFileContentProvider;
import consulo.versionControlSystem.change.IgnoredFileDescriptor;
import consulo.versionControlSystem.change.IgnoredFileProvider;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryFiles;
import git4idea.repo.GitRepositoryManager;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@ExtensionImpl
public class GitIgnoredFileContentProvider implements IgnoredFileContentProvider {
    private static final Logger LOG = Logger.getInstance(GitIgnoredFileContentProvider.class);

    private final Project myProject;

    private final GitIgnoreChecker myGitIgnoreChecker = new GitIgnoreChecker();

    @Inject
    public GitIgnoredFileContentProvider(Project project) {
        myProject = project;
    }

    @Override
    public VcsKey getSupportedVcs() {
        return GitVcs.getKey();
    }

    @Override
    public String getFileName() {
        return GitRepositoryFiles.GITIGNORE;
    }

    @Override
    public String buildIgnoreFileContent(VirtualFile ignoreFileRoot, IgnoredFileProvider[] ignoredFileProviders) {
        if (!GitUtil.isUnderGit(ignoreFileRoot)) {
            return ""; //if ignore file root not under git --> return (e.g. in case if .git folder was deleted externally)
        }
        VirtualFile ignoreFileVcsRoot = VcsUtil.getVcsRootFor(myProject, ignoreFileRoot);
        if (ignoreFileVcsRoot == null) {
            return "";
        }

        StringBuilder content = new StringBuilder();
        String lineSeparator = System.lineSeparator();
        Set<FilePath> untrackedFiles = getUntrackedFiles(ignoreFileVcsRoot);

        if (untrackedFiles.isEmpty()) {
            return ""; //if there is no untracked files this mean nothing to ignore
        }

        for (IgnoredFileProvider provider : ignoredFileProviders) {
            List<String> ignoredFiles =
                ignoreBeansToRelativePaths(provider.getIgnoredFiles(), ignoreFileVcsRoot, ignoreFileRoot, untrackedFiles);

            if (ignoredFiles.isEmpty()) {
                continue;
            }

            if (!content.isEmpty()) {
                content.append(lineSeparator).append(lineSeparator);
            }

            String description = provider.getIgnoredGroupDescription();
            if (!description.isBlank()) {
                content.append(buildIgnoreGroupDescription(provider));
                content.append(lineSeparator);
            }
            content.append(String.join(lineSeparator, ignoredFiles));
        }
        return content.toString();
    }

    private Set<FilePath> getUntrackedFiles(VirtualFile ignoreFileVcsRoot) {
        try {
            GitRepository repo = GitRepositoryManager.getInstance(myProject).getRepositoryForRoot(ignoreFileVcsRoot);
            if (repo == null) {
                return Collections.emptySet();
            }
            return repo.getUntrackedFilesHolder().retrieveUntrackedFiles()
                .stream()
                .map(VcsUtil::getFilePath)
                .collect(Collectors.toSet());
        }
        catch (VcsException e) {
            LOG.warn("Cannot get untracked files: ", e);
            return Collections.emptySet();
        }
    }

    private List<String> ignoreBeansToRelativePaths(
        Iterable<IgnoredFileDescriptor> iterable,
        VirtualFile ignoreFileVcsRoot,
        VirtualFile ignoreFileRoot,
        Set<FilePath> untrackedFiles
    ) {
        VcsContextFactory vcsContextFactory = VcsContextFactory.getInstance();
        List<String> result = new ArrayList<>();
        for (IgnoredFileDescriptor ignoredBean : iterable) {
            boolean shouldIgnore = switch (ignoredBean.getType()) {
                case UNDER_DIR -> shouldIgnoreUnderDir(ignoredBean, untrackedFiles, ignoreFileRoot, ignoreFileVcsRoot, vcsContextFactory);
                case FILE -> shouldIgnoreFile(ignoredBean, untrackedFiles, ignoreFileRoot, ignoreFileVcsRoot, vcsContextFactory);
                case MASK -> shouldIgnoreByMask(ignoredBean, untrackedFiles);
            };
            if (!shouldIgnore) {
                continue;
            }

            result.add(switch (ignoredBean.getType()) {
                case MASK -> Objects.requireNonNull(ignoredBean.getMask());
                case UNDER_DIR, FILE -> buildIgnoreEntryContent(ignoreFileRoot, ignoredBean);
            });
        }
        return result;
    }

    private boolean shouldIgnoreUnderDir(
        IgnoredFileDescriptor ignoredBean,
        Set<FilePath> untrackedFiles,
        VirtualFile ignoreFileRoot,
        VirtualFile ignoreFileVcsRoot,
        VcsContextFactory vcsContextFactory
    ) {
        String path = ignoredBean.getPath();
        if (path == null) {
            return false;
        }
        Path file = Paths.get(path);
        return Files.exists(file)
            && untrackedFiles.stream().anyMatch(it -> FileUtil.isAncestor(path, it.getPath(), true))
            && FileUtil.isAncestor(ignoreFileRoot.getPath(), path, false)
            && Objects.equals(ignoreFileVcsRoot, VcsUtil.getVcsRootFor(myProject, vcsContextFactory.createFilePath(path, true)))
            && myGitIgnoreChecker.isIgnored(ignoreFileVcsRoot, file) instanceof NotIgnored
            && shouldNotConsiderInternalIgnoreFile(ignoredBean, ignoreFileRoot);
    }

    private boolean shouldIgnoreFile(
        IgnoredFileDescriptor ignoredBean,
        Set<FilePath> untrackedFiles,
        VirtualFile ignoreFileRoot,
        VirtualFile ignoreFileVcsRoot,
        VcsContextFactory vcsContextFactory
    ) {
        String path = ignoredBean.getPath();
        return path != null
            && Files.exists(Paths.get(path))
            && untrackedFiles.stream().anyMatch(ignoredBean::matchesFile)
            && FileUtil.isAncestor(ignoreFileRoot.getPath(), path, false)
            && Objects.equals(ignoreFileVcsRoot, VcsUtil.getVcsRootFor(myProject, vcsContextFactory.createFilePath(path, false)))
            && shouldNotConsiderInternalIgnoreFile(ignoredBean, ignoreFileRoot);
    }

    private static boolean shouldIgnoreByMask(IgnoredFileDescriptor ignoredBean, Set<FilePath> untrackedFiles) {
        return untrackedFiles.stream().anyMatch(ignoredBean::matchesFile);
    }

    private boolean shouldNotConsiderInternalIgnoreFile(IgnoredFileDescriptor ignoredBean, VirtualFile ignoreFileRoot) {
        String path = ignoredBean.getPath();
        boolean insideDirectoryStore = path != null && path.contains(Project.DIRECTORY_STORE_FOLDER);
        if (insideDirectoryStore) {
            Path directoryStoreOrProjectFileLocation = getDirectoryStoreOrProjectFileLocation();
            if (directoryStoreOrProjectFileLocation == null) {
                return false;
            }
            return FileUtil.isAncestor(
                directoryStoreOrProjectFileLocation.toString(),
                FileUtil.toSystemDependentName(ignoreFileRoot.getPath()),
                false
            );
        }
        return true;
    }

    private @Nullable Path getDirectoryStoreOrProjectFileLocation() {
        String basePath = myProject.getBasePath();
        if (basePath != null) {
            return Paths.get(basePath, Project.DIRECTORY_STORE_FOLDER);
        }
        String projectFilePath = myProject.getProjectFilePath();
        return projectFilePath != null ? Paths.get(projectFilePath).getParent() : null;
    }

    @Override
    public String buildUnignoreContent(String ignorePattern) {
        StringBuilder builder = new StringBuilder();
        builder.append(System.lineSeparator());
        builder.append('!').append(ignorePattern);
        return builder.toString();
    }

    @Override
    public String buildIgnoreGroupDescription(IgnoredFileProvider ignoredFileProvider) {
        return prependCommentHashCharacterIfNeeded(ignoredFileProvider.getIgnoredGroupDescription());
    }

    @Override
    public String buildIgnoreEntryContent(VirtualFile ignoreEntryRoot, IgnoredFileDescriptor ignoredFileDescriptor) {
        String relativePath = FileUtil.getRelativePath(ignoreEntryRoot.getPath(), Objects.requireNonNull(ignoredFileDescriptor.getPath()), '/');
        return "/" + (relativePath != null ? relativePath : "");
    }

    private static String prependCommentHashCharacterIfNeeded(String description) {
        return description.startsWith("#") ? description : "# " + description;
    }

    /**
     * Disable creating a new .consulo/.gitignore when ignoring unversioned files.
     */
    @Override
    public boolean canCreateIgnoreFileInStateStoreDir() {
        return false;
    }
}
