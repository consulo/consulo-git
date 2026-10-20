// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.ignore.actions;

import consulo.application.progress.ProgressBuilderFactory;
import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.util.concurrent.coroutine.Coroutine;
import consulo.util.concurrent.coroutine.step.CodeExecution;
import consulo.versionControlSystem.AbstractVcs;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.change.ChangeListManager;
import consulo.versionControlSystem.change.IgnoredBeanFactory;
import consulo.versionControlSystem.change.IgnoredFileBean;
import consulo.versionControlSystem.change.action.ScheduleForAdditionAction;
import consulo.versionControlSystem.change.ignore.action.IgnoreFileAction;
import consulo.versionControlSystem.localize.VcsLocalize;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.repo.GitRepository;

import java.util.List;
import java.util.Map;

public class AddToGitExcludeAction extends DefaultGitExcludeAction {
    public AddToGitExcludeAction() {
        super(GitLocalize.gitAddToExcludeFileActionText(), GitLocalize.gitAddToExcludeFileActionDescription());
    }

    @Override
    protected boolean isEnabled(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        if (project == null) {
            return false;
        }
        List<VirtualFile> selectedFiles = IgnoreFileAction.getSelectedFiles(e);
        List<VirtualFile> unversionedFiles = ScheduleForAdditionAction.getUnversionedFiles(e, project).toList();
        return isEnabled(project, selectedFiles, unversionedFiles);
    }

    boolean isEnabled(Project project, List<VirtualFile> selectedFiles, List<VirtualFile> unversionedFiles) {
        ChangeListManager changeListManager = ChangeListManager.getInstance(project);
        //ScheduleForAdditionAction.getUnversionedFiles can return already ignored directories for VCS which doesn't support directory versioning, should filter it here
        if (unversionedFiles.stream().noneMatch(it -> !it.isDirectory() || !changeListManager.isIgnoredFile(it))) {
            return false;
        }
        ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(project);
        return selectedFiles.stream().anyMatch(it -> {
            AbstractVcs vcs = vcsManager.getVcsFor(it);
            return vcs != null && GitVcs.NAME.equals(vcs.getName());
        });
    }

    @Override
    @RequiredUIAccess
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        if (project == null) {
            return;
        }
        GitVcs gitVcs = GitVcs.getInstance(project);
        List<VirtualFile> selectedFiles = IgnoreFileAction.getSelectedFiles(e);
        if (selectedFiles.isEmpty()) {
            return;
        }

        project.getApplication().getInstance(ProgressBuilderFactory.class)
            .newProgressBuilder(project, VcsLocalize.ignoringFilesProgressTitle())
            .modal()
            .execute(
                project.getUIAccess(),
                () -> Coroutine.first(CodeExecution.<Object, Map<GitRepository, List<VirtualFile>>>apply(
                    (input, continuation) -> GitUtil.sortFilesByRepositoryIgnoringMissing(project, selectedFiles)
                ))
            )
            .whenCompleteAsync((filesToIgnore, throwable) -> {
                if (filesToIgnore == null || project.isDisposed()) {
                    return;
                }
                for (Map.Entry<GitRepository, List<VirtualFile>> entry : filesToIgnore.entrySet()) {
                    GitRepository repository = entry.getKey();
                    VirtualFile gitExclude = LocalFileSystem.getInstance()
                        .refreshAndFindFileByIoFile(repository.getRepositoryFiles().getExcludeFile());
                    if (gitExclude == null) {
                        continue;
                    }
                    List<IgnoredFileBean> ignores = entry.getValue().stream()
                        .map(file -> IgnoredBeanFactory.ignoreFile(file, project))
                        .toList();
                    IgnoreFileAction.writeIgnoreFileEntries(project, gitExclude, ignores, gitVcs, repository.getRoot());
                }
            }, project.getUIAccess());
    }
}
