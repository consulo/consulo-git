// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.index;

import consulo.logging.Logger;
import consulo.process.ProcessOutputTypes;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.util.dataholder.Key;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.log.Hash;
import consulo.versionControlSystem.log.base.HashImpl;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitHandlerInputProcessorUtil;
import git4idea.commands.GitLineHandler;
import git4idea.commands.GitLineHandlerAdapter;
import git4idea.repo.GitRepository;
import git4idea.util.StringScanner;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class GitIndexUtil {
    private static final Logger LOG = Logger.getInstance(GitIndexUtil.class);

    public static final String EXECUTABLE_MODE = "100755";
    public static final String DEFAULT_MODE = "100644";
    public static final String SUBMODULE_MODE = "160000";

    private GitIndexUtil() {
    }

    /**
     * In index-related Git operations zeroed-out hash is used to indicate that the file is not present.
     */
    public static boolean isNullHash(String hash) {
        return hash.chars().allMatch(c -> c == '0');
    }

    public static @Nullable StagedFile listStaged(GitRepository repository, FilePath filePath) throws VcsException {
        List<StagedFile> result = listStaged(repository, Collections.singleton(filePath));
        if (result.size() != 1) {
            return null;
        }
        return result.get(0);
    }

    public static List<StagedFile> listStaged(GitRepository repository, Collection<? extends FilePath> filePaths) throws VcsException {
        Project project = repository.getProject();
        VirtualFile root = repository.getRoot();

        return listStaged(project, root, filePaths);
    }

    public static List<StagedFile> listStaged(
        Project project,
        VirtualFile root,
        Collection<? extends FilePath> filePaths
    ) throws VcsException {
        List<StagedFile> result = new ArrayList<>();

        GitLineHandler h = new GitLineHandler(project, root, GitCommand.LS_FILES);
        h.addParameters("-s");
        h.endOptions();
        h.addRelativePaths(new ArrayList<FilePath>(filePaths));

        h.addLineListener(new GitLineHandlerAdapter() {
            @Override
            public void onLineAvailable(String line, Key outputType) {
                if (ProcessOutputTypes.STDOUT != outputType) {
                    return;
                }
                ContainerUtil.addIfNotNull(result, parseListFilesStagedRecord(root, line));
            }
        });
        Git.getInstance().runCommand(h).getOutputOrThrow();

        return result;
    }

    private static @Nullable StagedFile parseListFilesStagedRecord(VirtualFile root, String line) {
        try {
            StringScanner s = new StringScanner(line);
            String permissions = s.spaceToken();
            String hash = s.spaceToken();
            String stage = s.tabToken();
            String filePath = s.line();

            if (!"0".equals(stage)) {
                return null;
            }

            FilePath path = VcsUtil.getFilePath(root, GitUtil.unescapePath(filePath));
            boolean executable = EXECUTABLE_MODE.equals(permissions);
            return new StagedFile(path, hash, executable);
        }
        catch (VcsException e) {
            LOG.warn(e);
            return null;
        }
    }

    public static Hash write(GitRepository repository, FilePath filePath, byte[] bytes, boolean executable) throws VcsException {
        return write(repository, filePath, new ByteArrayInputStream(bytes), executable);
    }

    public static Hash write(GitRepository repository, FilePath filePath, InputStream content, boolean executable) throws VcsException {
        return write(repository.getProject(), repository.getRoot(), filePath, content, executable);
    }

    public static Hash write(Project project, VirtualFile root, FilePath filePath, InputStream content, boolean executable)
        throws VcsException {
        return write(project, root, filePath, content, executable, false);
    }

    public static Hash write(Project project,
                             VirtualFile root,
                             FilePath filePath,
                             InputStream content,
                             boolean executable,
                             boolean addNewFiles) throws VcsException {
        Hash hash = hashObject(project, root, filePath, content);
        updateIndex(project, root, filePath, hash, executable, addNewFiles);
        return hash;
    }

    private static Hash hashObject(Project project, VirtualFile root, FilePath filePath, InputStream content) throws VcsException {
        GitLineHandler h = new GitLineHandler(project, root, GitCommand.HASH_OBJECT);
        h.setSilent(true);
        h.addParameters("-w", "--stdin");
        h.addParameters("--path");
        h.addRelativePaths(filePath);
        h.setInputProcessor(GitHandlerInputProcessorUtil.redirectStream(content));
        h.endOptions();
        String output = Git.getInstance().runCommand(h).getOutputOrThrow();
        return HashImpl.build(output.trim());
    }

    public static void updateIndex(GitRepository repository, FilePath filePath, Hash blobHash, boolean isExecutable) throws VcsException {
        updateIndex(repository.getProject(), repository.getRoot(), filePath, blobHash, isExecutable, false);
    }

    public static void updateIndex(Project project,
                                   VirtualFile root,
                                   FilePath filePath,
                                   Hash blobHash,
                                   boolean isExecutable,
                                   boolean addNewFiles) throws VcsException {
        String mode = isExecutable ? EXECUTABLE_MODE : DEFAULT_MODE;
        String path = VcsFileUtil.relativePath(root, filePath);

        GitLineHandler h = new GitLineHandler(project, root, GitCommand.UPDATE_INDEX);
        if (addNewFiles) {
            h.addParameters("--add");
        }
        h.addParameters("--cacheinfo", mode + "," + blobHash.asString() + "," + path);
        h.endOptions();
        Git.getInstance().runCommand(h).throwOnError();
    }

    public static class StagedFileOrDirectory {
        protected final FilePath myPath;

        public StagedFileOrDirectory(FilePath path) {
            myPath = path;
        }

        public FilePath getPath() {
            return myPath;
        }

        @Override
        public String toString() {
            return "StagedFileOrDirectory[" + myPath + "]";
        }
    }

    public static class StagedFile extends StagedFileOrDirectory {
        private final String myBlobHash;
        private final boolean myExecutable;

        public StagedFile(FilePath path, String blobHash, boolean executable) {
            super(path);
            myBlobHash = blobHash;
            myExecutable = executable;
        }

        public String getBlobHash() {
            return myBlobHash;
        }

        public boolean isExecutable() {
            return myExecutable;
        }

        @Override
        public String toString() {
            return "StagedFile[" + myPath + "] at [" + myBlobHash + "]";
        }
    }
}
