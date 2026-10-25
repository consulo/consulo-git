// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote.hosting.action;

import consulo.application.ReadAction;
import consulo.codeEditor.Caret;
import consulo.codeEditor.Editor;
import consulo.document.Document;
import consulo.project.Project;
import consulo.util.lang.Range;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.change.ChangeListManager;
import consulo.versionControlSystem.log.CommitId;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.status.FileStatus;
import git4idea.GitFileRevision;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.remote.hosting.HostedGitRepositoriesManager;
import git4idea.remote.hosting.HostedGitRepositoryMapping;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public final class HostedGitRepositoryReferenceUtil {
    private HostedGitRepositoryReferenceUtil() {
    }

    private static List<HostedGitRepositoryReference> findReferences(HostedGitRepositoriesManager<?> repositoryManager,
                                                                     GitRepository repository,
                                                                     GitRevisionNumber revision,
                                                                     BiFunction<URI, String, URI> uriProducer) {
        List<? extends HostedGitRepositoryMapping> accessibleRepositories = repositoryManager.findKnownRepositories(repository);
        if (accessibleRepositories.isEmpty()) {
            return List.of();
        }

        List<HostedGitRepositoryReference> result = new ArrayList<>(accessibleRepositories.size());
        for (HostedGitRepositoryMapping it : accessibleRepositories) {
            result.add(new HostedGitRepositoryWebURIReference(uriProducer.apply(it.getRepository().getWebURI(), revision.asString())));
        }
        return result;
    }

    public static List<HostedGitRepositoryReference> findReferences(Project project,
                                                                    HostedGitRepositoriesManager<?> repositoryManager,
                                                                    VirtualFile file,
                                                                    GitRevisionNumber revision,
                                                                    BiFunction<URI, String, URI> uriProducer) {
        FilePath filePath = VcsUtil.getFilePath(file);
        GitRepository repository = GitUtil.getRepositoryManager(project).getRepositoryForFileQuick(filePath);
        if (repository == null) {
            return List.of();
        }
        return findReferences(repositoryManager, repository, revision, uriProducer);
    }

    public static List<HostedGitRepositoryReference> findReferences(Project project,
                                                                    HostedGitRepositoriesManager<?> repositoryManager,
                                                                    GitFileRevision revision,
                                                                    BiFunction<URI, String, URI> uriProducer) {
        GitRepository repository = GitUtil.getRepositoryManager(project).getRepositoryForFileQuick(revision.getPath());
        if (repository == null) {
            return List.of();
        }
        if (!(revision.getRevisionNumber() instanceof GitRevisionNumber revisionNumber)) {
            return List.of();
        }
        return findReferences(repositoryManager, repository, revisionNumber, uriProducer);
    }

    public static List<HostedGitRepositoryReference> findReferences(Project project,
                                                                    HostedGitRepositoriesManager<?> repositoryManager,
                                                                    CommitId commit,
                                                                    BiFunction<URI, String, URI> uriProducer) {
        GitRepository repository = GitUtil.getRepositoryManager(project).getRepositoryForRootQuick(commit.getRoot());
        if (repository == null) {
            return List.of();
        }

        List<? extends HostedGitRepositoryMapping> accessibleRepositories = repositoryManager.findKnownRepositories(repository);
        if (accessibleRepositories.isEmpty()) {
            return List.of();
        }

        List<HostedGitRepositoryReference> result = new ArrayList<>(accessibleRepositories.size());
        for (HostedGitRepositoryMapping it : accessibleRepositories) {
            result.add(new HostedGitRepositoryWebURIReference(uriProducer.apply(it.getRepository().getWebURI(), commit.getHash().asString())));
        }
        return result;
    }

    public static List<HostedGitRepositoryReference> findReferences(Project project,
                                                                    HostedGitRepositoriesManager<?> repositoryManager,
                                                                    VirtualFile virtualFile,
                                                                    @Nullable Editor editor,
                                                                    HostedGitRepositoryFileUriProducer uriProducer) {
        Range<Integer> lineRange = null;
        if (editor != null && editor.getDocument().getLineCount() >= 1) {
            lineRange = ReadAction.compute(() -> {
                Document document = editor.getDocument();
                Caret caret = editor.getCaretModel().getCurrentCaret();
                int selectionStart = caret.getSelectionStart();
                int begin = document.getLineNumber(selectionStart);

                int selectionEnd = caret.getSelectionEnd();
                int end = document.getLineNumber(selectionEnd);

                if (document.getLineStartOffset(end) == selectionEnd) {
                    end -= 1;
                }
                return new Range<>(begin, end);
            });
        }

        return findReferences(project, repositoryManager, virtualFile, lineRange, uriProducer);
    }

    public static List<HostedGitRepositoryReference> findReferences(Project project,
                                                                    HostedGitRepositoriesManager<?> repositoryManager,
                                                                    VirtualFile virtualFile,
                                                                    @Nullable Range<Integer> lineRange,
                                                                    HostedGitRepositoryFileUriProducer uriProducer) {
        if (virtualFile.isInLocalFileSystem()) {
            FileStatus status = ChangeListManager.getInstance(project).getStatus(virtualFile);
            if (status == FileStatus.UNKNOWN || status == FileStatus.ADDED || status == FileStatus.IGNORED) {
                return List.of();
            }
        }

        FilePath filePath = VcsUtil.getFilePath(virtualFile);
        GitRepository repository = GitUtil.getRepositoryManager(project).getRepositoryForFileQuick(filePath);
        if (repository == null) {
            return List.of();
        }

        List<? extends HostedGitRepositoryMapping> accessibleRepositories = repositoryManager.findKnownRepositories(repository);
        if (accessibleRepositories.isEmpty()) {
            return List.of();
        }

        String relativePath = VcsFileUtil.relativePath(repository.getRoot(), filePath);
        if (StringUtil.isEmptyOrSpaces(relativePath)) {
            return List.of();
        }

        List<HostedGitRepositoryReference> result = new ArrayList<>(accessibleRepositories.size());
        for (HostedGitRepositoryMapping it : accessibleRepositories) {
            result.add(new HostedGitRepositoryFileReference(project, it.getRepository().getWebURI(), filePath, relativePath, lineRange, uriProducer));
        }
        return result;
    }
}
