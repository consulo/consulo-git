// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote.hosting.action;

import consulo.codeEditor.Editor;
import consulo.dataContext.DataContext;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.image.Image;
import consulo.util.lang.Range;
import consulo.versionControlSystem.VcsDataKeys;
import consulo.versionControlSystem.history.VcsFileRevision;
import consulo.versionControlSystem.log.CommitId;
import consulo.versionControlSystem.log.VcsLogCommitSelection;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitFileRevision;
import git4idea.remote.hosting.HostedGitRepositoriesManager;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.List;

public abstract class GlobalHostedGitRepositoryReferenceActionGroup extends HostedGitRepositoryReferenceActionGroup {
    protected GlobalHostedGitRepositoryReferenceActionGroup() {
        super();
    }

    protected GlobalHostedGitRepositoryReferenceActionGroup(LocalizeValue dynamicText, LocalizeValue dynamicDescription, @Nullable Image icon) {
        super(dynamicText, dynamicDescription, icon);
    }

    protected abstract HostedGitRepositoriesManager<?> repositoriesManager(Project project);

    @Override
    protected List<HostedGitRepositoryReference> findReferences(DataContext dataContext) {
        Project project = dataContext.getData(Project.KEY);
        if (project == null) {
            return List.of();
        }

        List<HostedGitRepositoryReference> inHistory = findReferencesInHistory(project, dataContext);
        if (!inHistory.isEmpty()) {
            return inHistory;
        }
        List<HostedGitRepositoryReference> inLog = findReferencesInLog(project, dataContext);
        if (!inLog.isEmpty()) {
            return inLog;
        }
        return findReferencesInFile(project, dataContext);
    }

    private List<HostedGitRepositoryReference> findReferencesInHistory(Project project, DataContext dataContext) {
        VcsFileRevision fileRevision = dataContext.getData(VcsDataKeys.VCS_FILE_REVISION);
        if (!(fileRevision instanceof GitFileRevision gitFileRevision)) {
            return List.of();
        }
        return HostedGitRepositoryReferenceUtil.findReferences(project, repositoriesManager(project), gitFileRevision,
            (repository, revisionHash) -> getUri(project, repository, revisionHash));
    }

    private List<HostedGitRepositoryReference> findReferencesInLog(Project project, DataContext dataContext) {
        VcsLogCommitSelection selection = dataContext.getData(VcsLogCommitSelection.KEY);
        if (selection == null) {
            return List.of();
        }
        List<CommitId> commits = selection.getCommits();
        if (commits.isEmpty()) {
            return List.of();
        }
        return HostedGitRepositoryReferenceUtil.findReferences(project, repositoriesManager(project), commits.getFirst(),
            (repository, revisionHash) -> getUri(project, repository, revisionHash));
    }

    private List<HostedGitRepositoryReference> findReferencesInFile(Project project, DataContext dataContext) {
        VirtualFile virtualFile = dataContext.getData(VirtualFile.KEY);
        if (virtualFile == null) {
            return List.of();
        }
        Editor editor = dataContext.getData(Editor.KEY);

        return HostedGitRepositoryReferenceUtil.findReferences(project, repositoriesManager(project), virtualFile, editor,
            (repository, revisionHash, relativePath, lineRange) -> getUri(project, repository, revisionHash, relativePath, lineRange));
    }

    protected URI getUri(Project project, URI repository, String revisionHash) {
        return getUri(repository, revisionHash);
    }

    protected abstract URI getUri(URI repository, String revisionHash);

    protected URI getUri(Project project, URI repository, String revisionHash, String relativePath, @Nullable Range<Integer> lineRange) {
        return getUri(repository, revisionHash, relativePath, lineRange);
    }

    protected abstract URI getUri(URI repository, String revisionHash, String relativePath, @Nullable Range<Integer> lineRange);
}
