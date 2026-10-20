// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.ignore.actions;

import consulo.annotation.component.ActionImpl;
import consulo.git.localize.GitLocalize;
import consulo.navigation.OpenFileDescriptorFactory;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.repo.GitRepository;

@ActionImpl(id = "Git.OpenExcludeFile")
public class OpenGitExcludeAction extends DefaultGitExcludeAction {
    public OpenGitExcludeAction() {
        super(GitLocalize.gitOpenExcludeFileActionText(), GitLocalize.gitOpenExcludeFileActionDescription());
    }

    @Override
    @RequiredUIAccess
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        if (project == null) {
            return;
        }
        for (GitRepository repository : GitUtil.getRepositories(project)) {
            VirtualFile excludeVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repository.getRepositoryFiles().getExcludeFile());
            if (excludeVf != null) {
                OpenFileDescriptorFactory.getInstance(project).newBuilder(excludeVf).build().navigate(true);
            }
        }
    }
}
