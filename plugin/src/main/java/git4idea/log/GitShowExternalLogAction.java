/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package git4idea.log;

import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.application.progress.Task;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.fileChooser.FileChooserDescriptor;
import consulo.fileChooser.IdeaFileChooser;
import consulo.ide.ServiceManager;
import consulo.ide.impl.idea.openapi.ui.WindowWrapperBuilder;
import consulo.ide.impl.idea.openapi.vcs.changes.ui.ChangesViewContentManager;
import consulo.ide.impl.idea.vcs.log.data.VcsLogTabsProperties;
import consulo.ide.impl.idea.vcs.log.impl.VcsLogContentProvider;
import consulo.ide.impl.idea.vcs.log.impl.VcsLogManager;
import consulo.language.editor.CommonDataKeys;
import consulo.project.Project;
import consulo.project.ui.wm.ToolWindowManager;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.ex.awt.WindowWrapper;
import consulo.ui.ex.content.Content;
import consulo.ui.ex.content.ContentFactory;
import consulo.ui.ex.content.ContentManager;
import consulo.ui.ex.toolWindow.ToolWindow;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.Comparing;
import consulo.util.lang.ObjectUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.root.VcsRoot;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.config.GitVersion;
import git4idea.repo.GitRepositoryImpl;
import git4idea.repo.GitRepositoryManager;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class GitShowExternalLogAction extends DumbAwareAction {
  private static final String EXTERNAL = "EXTERNAL";

  @Override
  public void update(@Nonnull AnActionEvent e) {
    super.update(e);
    e.getPresentation().setEnabledAndVisible(e.getData(Project.KEY) != null && GitVcs.getInstance(e.getData(Project.KEY)) != null);
  }

  @Override
  public void actionPerformed(@Nonnull AnActionEvent e) {
    final Project project = e.getRequiredData(CommonDataKeys.PROJECT);
    final GitVcs vcs = ObjectUtil.assertNotNull(GitVcs.getInstance(project));
    final List<VirtualFile> roots = getGitRootsFromUser(project);
    if (roots.isEmpty()) {
      return;
    }

    if (project.isDefault() || !ProjectLevelVcsManager.getInstance(project).hasActiveVcss()) {
      ProgressManager.getInstance().run(new ShowLogInDialogTask(project, roots, vcs));
      return;
    }

    final ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(ChangesViewContentManager.TOOLWINDOW_ID);
    final Runnable showContent = () ->
    {
      ContentManager cm = window.getContentManager();
      if (checkIfProjectLogMatches(project, vcs, cm, roots) || checkIfAlreadyOpened(cm, roots)) {
        return;
      }

      String tabName = calcTabName(cm, roots);
      MyContentComponent component = createManagerAndContent(project, vcs, roots, tabName);
      Content content = ContentFactory.SERVICE.getInstance().createContent(component, tabName, false);
      content.setDisposer(component.myDisposable);
      content.setDescription("Log for " + StringUtil.join(roots, VirtualFile::getPath, "\n"));
      content.setCloseable(true);
      cm.addContent(content);
      cm.setSelectedContent(content);
    };

    if (!window.isVisible()) {
      window.activate(showContent, true);
    }
    else {
      showContent.run();
    }
  }

  @Nonnull
  private static MyContentComponent createManagerAndContent(@Nonnull Project project,
                                                            @Nonnull final GitVcs vcs,
                                                            @Nonnull final List<VirtualFile> roots,
                                                            @Nullable String tabName) {
    final GitRepositoryManager repositoryManager = ServiceManager.getService(project, GitRepositoryManager.class);
    for (VirtualFile root : roots) {
      repositoryManager.addExternalRepository(root, GitRepositoryImpl.getInstance(root, project, true));
    }
    VcsLogManager manager = new VcsLogManager(project,
																							ServiceManager.getService(project, VcsLogTabsProperties.class),
																							ContainerUtil.map(roots, root -> new VcsRoot(vcs, root)));
    return new MyContentComponent(manager.createLogPanel(calcLogId(roots), tabName), roots, () ->
    {
      for (VirtualFile root : roots) {
        repositoryManager.removeExternalRepository(root);
      }
    });
  }

  @Nonnull
  private static String calcLogId(@Nonnull List<VirtualFile> roots) {
    return EXTERNAL + " " + StringUtil.join(roots, VirtualFile::getPath, File.pathSeparator);
  }

  @Nonnull
  private static String calcTabName(@Nonnull ContentManager cm, @Nonnull List<VirtualFile> roots) {
    String name = VcsLogContentProvider.TAB_NAME + " (" + roots.get(0).getName();
    if (roots.size() > 1) {
      name += "+";
    }
    name += ")";

    String candidate = name;
    int cnt = 1;
    while (hasContentsWithName(cm, candidate)) {
      candidate = name + "-" + cnt;
      cnt++;
    }
    return candidate;
  }

  private static boolean hasContentsWithName(@Nonnull ContentManager cm, @Nonnull final String candidate) {
    return ContainerUtil.exists(cm.getContents(), content -> content.getDisplayName().equals(candidate));
  }

  @Nonnull
  private static List<VirtualFile> getGitRootsFromUser(@Nonnull Project project) {
    FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, true, false, true);
    VirtualFile[] virtualFiles = IdeaFileChooser.chooseFiles(descriptor, project, null);
    if (virtualFiles.length == 0) {
      return Collections.emptyList();
    }

    List<VirtualFile> correctRoots = ContainerUtil.newArrayList();
    for (VirtualFile vf : virtualFiles) {
      if (GitUtil.isGitRoot(new File(vf.getPath()))) {
        correctRoots.add(vf);
      }
    }
    return correctRoots;
  }

  private static boolean checkIfProjectLogMatches(@Nonnull Project project,
                                                  @Nonnull GitVcs vcs,
                                                  @Nonnull ContentManager cm,
                                                  @Nonnull List<VirtualFile> requestedRoots) {
    VirtualFile[] projectRoots = ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(vcs);
    if (Comparing.haveEqualElements(requestedRoots, Arrays.asList(projectRoots))) {
      Content[] contents = cm.getContents();
      for (Content content : contents) {
        if (VcsLogContentProvider.TAB_NAME.equals(content.getDisplayName())) {
          cm.setSelectedContent(content);
          return true;
        }
      }
    }
    return false;
  }

  private static boolean checkIfAlreadyOpened(@Nonnull ContentManager cm, @Nonnull Collection<VirtualFile> roots) {
    for (Content content : cm.getContents()) {
      final JComponent component = content.getComponent();
      if (component instanceof MyContentComponent) {
        if (Comparing.haveEqualElements(roots, ((MyContentComponent)component).myRoots)) {
          cm.setSelectedContent(content);
          return true;
        }
      }
    }
    return false;
  }

  private static class MyContentComponent extends JPanel {
    @Nonnull
    private final Collection<VirtualFile> myRoots;
    @Nonnull
    private final Disposable myDisposable;

    MyContentComponent(@Nonnull JComponent actualComponent, @Nonnull Collection<VirtualFile> roots, @Nonnull Disposable disposable) {
      super(new BorderLayout());
      myDisposable = disposable;
      myRoots = roots;
      add(actualComponent);
    }
  }

  private static class ShowLogInDialogTask extends Task.Backgroundable {
    @Nonnull
    private final Project myProject;
    @Nonnull
    private final List<VirtualFile> myRoots;
    @Nonnull
    private final GitVcs myVcs;
    private GitVersion myVersion;

    private ShowLogInDialogTask(@Nonnull Project project, @Nonnull List<VirtualFile> roots, @Nonnull GitVcs vcs) {
      super(project, "Loading Git Log...", true);
      myProject = project;
      myRoots = roots;
      myVcs = vcs;
    }

    @Override
    public void run(@Nonnull ProgressIndicator indicator) {
      myVersion = myVcs.getVersion();
      if (myVersion.isNull()) {
        myVcs.checkVersion();
        myVersion = myVcs.getVersion();
      }
    }

    @Override
    public void onSuccess() {
      if (!myVersion.isNull() && !myProject.isDisposed()) {
        MyContentComponent content = createManagerAndContent(myProject, myVcs, myRoots, null);
        WindowWrapper window = new WindowWrapperBuilder(WindowWrapper.Mode.FRAME, content).setProject(myProject)
																																													.setTitle("Git Log")
																																													.setPreferredFocusedComponent(content)
																																													.setDimensionServiceKey(GitShowExternalLogAction.class
                                                                                                                    .getName())
																																													.build();
        Disposer.register(window, content.myDisposable);
        window.show();
      }
    }
  }
}
