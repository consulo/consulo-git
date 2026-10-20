// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ignore;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.WriteAction;
import consulo.disposer.Disposable;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ProjectCoreUtil;
import consulo.project.ui.notification.NotificationService;
import consulo.util.io.FileUtil;
import consulo.util.lang.Pair;
import consulo.versionControlSystem.AbstractVcs;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsKey;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.VcsSharedChecker;
import consulo.versionControlSystem.change.IgnoreSettingsType;
import consulo.versionControlSystem.change.IgnoredFileContentProvider;
import consulo.versionControlSystem.change.IgnoredFileDescriptor;
import consulo.versionControlSystem.change.IgnoredFileGenerator;
import consulo.versionControlSystem.change.IgnoredFileProvider;
import consulo.versionControlSystem.change.ignore.IgnoreFileUtil;
import consulo.versionControlSystem.util.VcsImplUtil;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.NewVirtualFile;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.event.AsyncVfsEventsListener;
import consulo.virtualFileSystem.event.AsyncVfsEventsPostProcessor;
import consulo.virtualFileSystem.event.VFileEvent;
import git4idea.GitVcs;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static git4idea.repo.GitRepositoryFiles.GITIGNORE;

/**
 * Generate .consulo/.gitignore file silently after project create/open
 */
@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitIgnoreInStoreDirGenerator implements Disposable {
    private static final Logger LOG = Logger.getInstance(GitIgnoreInStoreDirGenerator.class);

    private final Project myProject;

    private final AtomicBoolean myNeedGenerate = new AtomicBoolean(true);

    @Inject
    public GitIgnoreInStoreDirGenerator(Project project) {
        myProject = project;
    }

    public static GitIgnoreInStoreDirGenerator getInstance(Project project) {
        return project.getInstance(GitIgnoreInStoreDirGenerator.class);
    }

    @Override
    public void dispose() {
    }

    public void run() {
        Supplier<Boolean> registerListener = this::registerVfsListenerIfNeeded;
        boolean listenerRegistered = myProject.getApplication().runReadAction(registerListener);
        if (!listenerRegistered) {
            generateGitignoreInStoreDirIfNeeded();
        }
    }

    private boolean registerVfsListenerIfNeeded() {
        Path projectConfigDirPath = getDirectoryStorePath(myProject);
        if (projectConfigDirPath == null) {
            LOG.warn("Project config dir path not found. Project is default or not directory based.");
            myNeedGenerate.set(false);
            return false;
        }

        VirtualFile projectConfigDirVFile = ProjectCoreUtil.getDirectoryStoreFile(myProject);
        if (projectConfigDirVFile != null && !generator().needGenerateInternalIgnoreFile(projectConfigDirVFile)) {
            myNeedGenerate.set(false);
            return false;
        }

        boolean needRegister = projectConfigDirVFile == null;
        if (needRegister) {
            LOG.debug("Project config directory doesn't exist. Register VFS listener and try generate " + GITIGNORE
                + " after config directory become available.");
            AsyncVfsEventsPostProcessor.getInstance().addListener(new VfsEventsListener(), this);
        }
        return needRegister;
    }

    private class VfsEventsListener implements AsyncVfsEventsListener {
        @Override
        public void filesChanged(List<? extends VFileEvent> events) {
            if (!myNeedGenerate.get() || myProject.isDisposed()) {
                return;
            }

            if (affectedFilesInStoreDir(events)) {
                myProject.getApplication().executeOnPooledThread(GitIgnoreInStoreDirGenerator.this::generateGitignoreInStoreDirIfNeeded);
            }
        }

        private boolean affectedFilesInStoreDir(List<? extends VFileEvent> events) {
            Path projectConfigDirPath = getDirectoryStorePath(myProject);
            if (projectConfigDirPath == null) {
                return false;
            }
            String configDirPath = FileUtil.toSystemIndependentName(projectConfigDirPath.toString());
            for (VFileEvent event : events) {
                VirtualFile file = event.getFile();
                if (file != null && file.isInLocalFileSystem() && file.exists() && inStoreDir(configDirPath, file.getPath(), true)) {
                    return true;
                }
            }
            return false;
        }
    }

    public void generateGitignoreInStoreDirIfNeededSync() {
        generateGitignoreInStoreDirIfNeeded();
    }

    public void generateGitignoreInStoreDirIfNeeded() {
        if (!myNeedGenerate.compareAndSet(true, false)) {
            return;
        }

        Path projectConfigDirPath = getDirectoryStorePath(myProject);
        if (projectConfigDirPath == null) {
            LOG.warn("Project config dir path not found. Project is default or not directory based.");
            return;
        }

        VirtualFile projectConfigDirVFile = ProjectCoreUtil.getDirectoryStoreFile(myProject);
        if (projectConfigDirVFile == null) {
            LOG.warn("Project config dir not found in VFS by path " + projectConfigDirPath);
            return;
        }

        if (skipGeneration(projectConfigDirVFile, projectConfigDirPath)) {
            return;
        }

        try {
            doGenerate(projectConfigDirPath, projectConfigDirVFile);
        }
        catch (IOException e) {
            LOG.warn(e);
            NotificationService.getInstance()
                .newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(GitLocalize.notificationIgnoreFileGenerationErrorTextFilesProgressTitle())
                .content(LocalizeValue.of(e.getMessage() != null ? e.getMessage() : ""))
                .notify(myProject);
        }
    }

    private boolean skipGeneration(VirtualFile projectConfigDirVFile, Path projectConfigDirPath) {
        if (!generator().needGenerateInternalIgnoreFile(projectConfigDirVFile)) {
            myNeedGenerate.set(false);
            return true;
        }
        if (refreshAndFindChild(projectConfigDirVFile, GITIGNORE) != null) {
            markGenerated(projectConfigDirVFile);
            return true;
        }
        if (isProjectSharedInVcs(projectConfigDirPath)) {
            markGenerated(projectConfigDirVFile);
            return true;
        }
        return false;
    }

    private void doGenerate(Path projectConfigDirPath, VirtualFile projectConfigDirVFile) throws IOException {
        VcsKey gitVcsKey = GitVcs.getKey();
        IgnoredFileContentProvider gitIgnoreContentProvider = VcsImplUtil.findIgnoredFileContentProvider(myProject, gitVcsKey);
        if (gitIgnoreContentProvider == null) {
            return;
        }

        LOG.debug("Generate " + GITIGNORE + " in " + projectConfigDirPath + " for " + gitVcsKey.getName());

        VirtualFile gitIgnoreFile = WriteAction.computeAndWait(() -> {
            try {
                return projectConfigDirVFile.createChildData(projectConfigDirVFile, GITIGNORE);
            }
            catch (IOException e) {
                LOG.warn(e);
                return null;
            }
        });
        if (gitIgnoreFile == null) {
            throw new IOException("Cannot create " + GITIGNORE + " in " + projectConfigDirPath);
        }

        String configDirPath = FileUtil.toSystemIndependentName(projectConfigDirPath.toString());
        List<Pair<String, List<IgnoredFileDescriptor>>> groupedEntries = new ArrayList<>();
        for (IgnoredFileProvider ignoredFileProvider : myProject.getExtensionPoint(IgnoredFileProvider.class).getExtensionList()) {
            List<IgnoredFileDescriptor> ignoresInStoreDir = new ArrayList<>();
            for (IgnoredFileDescriptor ignore : ignoredFileProvider.getIgnoredFiles()) {
                if (inStoreDir(configDirPath, ignore)) {
                    ignoresInStoreDir.add(ignore);
                }
            }
            if (ignoresInStoreDir.isEmpty()) {
                continue;
            }
            String ignoredGroupDescription = gitIgnoreContentProvider.buildIgnoreGroupDescription(ignoredFileProvider);
            groupedEntries.add(Pair.create(ignoredGroupDescription, ignoresInStoreDir));
        }

        if (groupedEntries.isEmpty() || groupedEntries.stream().allMatch(it -> it.getSecond().isEmpty())) {
            return;
        }

        IgnoreFileUtil.addNewElementsToIgnoreFile(myProject, gitIgnoreFile, groupedEntries, gitVcsKey);
        markGenerated(projectConfigDirVFile);
    }

    private boolean isProjectSharedInVcs(Path projectConfigDirPath) {
        AbstractVcs vcs = VcsUtil.getVcsFor(myProject, VcsUtil.getFilePath(projectConfigDirPath.toFile(), true));
        if (vcs == null) {
            return false;
        }
        try {
            VcsSharedChecker checker = myProject.getApplication().getExtensionPoint(VcsSharedChecker.class)
                .findFirstSafe(it -> it.getSupportedVcs().equals(vcs.getKeyInstanceMethod()));
            if (checker != null) {
                return checker.isPathSharedInVcs(myProject, projectConfigDirPath);
            }
        }
        catch (VcsException e) {
            LOG.debug("Cannot check staged files in " + projectConfigDirPath, e);
        }
        return false;
    }

    private static @Nullable VirtualFile refreshAndFindChild(VirtualFile directory, String name) {
        if (directory instanceof NewVirtualFile newVirtualFile) {
            return newVirtualFile.refreshAndFindChild(name);
        }
        return directory.findChild(name);
    }

    private void markGenerated(VirtualFile projectConfigDirVFile) {
        generator().markIgnoreFileRootAsGenerated(projectConfigDirVFile.getPath());
        myNeedGenerate.set(false);
    }

    private IgnoredFileGenerator generator() {
        return myProject.getInstance(IgnoredFileGenerator.class);
    }

    private static boolean inStoreDir(String projectConfigDirPath, IgnoredFileDescriptor ignore) {
        String path = ignore.getPath();
        if (path == null) {
            return false;
        }
        return inStoreDir(projectConfigDirPath, path, ignore.getType() != IgnoreSettingsType.MASK);
    }

    private static boolean inStoreDir(String projectConfigDirPath, String path, boolean strict) {
        return FileUtil.isAncestor(projectConfigDirPath, path, strict);
    }

    static @Nullable Path getDirectoryStorePath(Project project) {
        if (!ProjectCoreUtil.isDirectoryBased(project)) {
            return null;
        }
        String basePath = project.getBasePath();
        return basePath != null ? new File(basePath, Project.DIRECTORY_STORE_FOLDER).toPath() : null;
    }
}
