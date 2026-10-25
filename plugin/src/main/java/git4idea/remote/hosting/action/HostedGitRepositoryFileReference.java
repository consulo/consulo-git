// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote.hosting.action;

import consulo.application.progress.ProgressManager;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.util.lang.Range;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsNotifier;
import git4idea.GitRevisionNumber;
import git4idea.history.GitHistoryUtils;
import org.jspecify.annotations.Nullable;

import java.net.URI;

public record HostedGitRepositoryFileReference(Project project,
                                               URI repository,
                                               FilePath filePath,
                                               String relativePath,
                                               @Nullable Range<Integer> lineRange,
                                               HostedGitRepositoryFileUriProducer uriProducer) implements HostedGitRepositoryReference {
    @Override
    public LocalizeValue getName() {
        return LocalizeValue.of(repository.toString().replace('_', ' '));
    }

    @Override
    public @Nullable URI buildWebURI() {
        //TODO: check pushed
        String hash = getCurrentFileRevisionHash(project, filePath);
        if (hash == null) {
            NotificationService.getInstance()
                .newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(GitLocalize.openInBrowserError())
                .content(GitLocalize.openInBrowserGettingRevisionError())
                .notify(project);
            return null;
        }

        return uriProducer.produce(repository, hash, relativePath, lineRange);
    }

    private static @Nullable String getCurrentFileRevisionHash(Project project, FilePath filePath) {
        GitRevisionNumber revision;
        try {
            revision = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> (GitRevisionNumber) GitHistoryUtils.getCurrentRevision(project, filePath, "HEAD"),
                GitLocalize.openInBrowserGettingRevision(),
                true,
                project
            );
        }
        catch (Exception e) {
            revision = null;
        }
        return revision == null ? null : revision.getRev();
    }
}
