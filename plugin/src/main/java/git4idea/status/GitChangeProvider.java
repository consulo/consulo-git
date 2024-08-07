/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package git4idea.status;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.progress.ProgressIndicator;
import consulo.document.FileDocumentManager;
import consulo.ide.impl.idea.openapi.util.io.FileUtil;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsKey;
import consulo.versionControlSystem.action.VcsContextFactory;
import consulo.versionControlSystem.change.*;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.status.FileStatus;
import git4idea.GitContentRevision;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.changes.GitChangeUtils;
import git4idea.commands.Git;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.*;

/**
 * Git repository change provider
 */
@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitChangeProvider implements ChangeProvider {

  private static final Logger PROFILE_LOG = Logger.getInstance("#GitStatus");

  @Nonnull
  private final Project myProject;
  @Nonnull
  private final Git myGit;
  @Nonnull
  private final ChangeListManager myChangeListManager;
  @Nonnull
  private final FileDocumentManager myFileDocumentManager;
  @Nonnull
  private final ProjectLevelVcsManager myVcsManager;

  @Inject
  public GitChangeProvider(@Nonnull Project project, @Nonnull Git git, @Nonnull ChangeListManager changeListManager,
                           @Nonnull FileDocumentManager fileDocumentManager, @Nonnull ProjectLevelVcsManager vcsManager) {
    myProject = project;
    myGit = git;
    myChangeListManager = changeListManager;
    myFileDocumentManager = fileDocumentManager;
    myVcsManager = vcsManager;
  }

  @Override
  public void getChanges(final VcsDirtyScope dirtyScope,
                         final ChangelistBuilder builder,
                         final ProgressIndicator progress,
                         final ChangeListManagerGate addGate) throws VcsException {
    final GitVcs vcs = GitVcs.getInstance(myProject);
    if (vcs == null) {
      // already disposed or not yet initialized => ignoring
      return;
    }

    appendNestedVcsRootsToDirt(dirtyScope, vcs, myVcsManager);

    final Collection<VirtualFile> affected = dirtyScope.getAffectedContentRoots();
    Collection<VirtualFile> roots = GitUtil.gitRootsForPaths(affected);

    try {
      final MyNonChangedHolder holder = new MyNonChangedHolder(myProject, dirtyScope.getDirtyFilesNoExpand(), addGate,
                                                               myFileDocumentManager, myVcsManager);
      for (VirtualFile root : roots) {
        debug("checking root: " + root.getPath());
        GitChangesCollector collector = GitNewChangesCollector.collect(myProject, myGit, myChangeListManager, myVcsManager,
            vcs, dirtyScope, root);
        final Collection<Change> changes = collector.getChanges();
        holder.changed(changes);
        for (Change file : changes) {
          debug("process change: " + ChangesUtil.getFilePath(file).getPath());
          builder.processChange(file, GitVcs.getKey());
        }
        for (VirtualFile f : collector.getUnversionedFiles()) {
          builder.processUnversionedFile(f);
          holder.unversioned(f);
        }
        holder.feedBuilder(builder);
      }
    }
    catch (VcsException e) {
      PROFILE_LOG.info(e);
      // most probably the error happened because git is not configured
      vcs.getExecutableValidator().showNotificationOrThrow(e);
    }
  }

  public static void appendNestedVcsRootsToDirt(final VcsDirtyScope dirtyScope, GitVcs vcs, final ProjectLevelVcsManager vcsManager) {
    final Set<FilePath> recursivelyDirtyDirectories = dirtyScope.getRecursivelyDirtyDirectories();
    if (recursivelyDirtyDirectories.isEmpty()) {
      return;
    }

    final LocalFileSystem lfs = LocalFileSystem.getInstance();
    final Set<VirtualFile> rootsUnderGit = new HashSet<VirtualFile>(Arrays.asList(vcsManager.getRootsUnderVcs(vcs)));
    final Set<VirtualFile> inputColl = new HashSet<VirtualFile>(rootsUnderGit);
    final Set<VirtualFile> existingInScope = new HashSet<VirtualFile>();
    for (FilePath dir : recursivelyDirtyDirectories) {
      VirtualFile vf = dir.getVirtualFile();
      if (vf == null) {
        vf = lfs.findFileByIoFile(dir.getIOFile());
      }
      if (vf == null) {
        vf = lfs.refreshAndFindFileByIoFile(dir.getIOFile());
      }
      if (vf != null) {
        existingInScope.add(vf);
      }
    }
    inputColl.addAll(existingInScope);
    FileUtil.removeAncestors(inputColl, o -> o.getPath(), (parent, child) -> {
                               if (!existingInScope.contains(child) && existingInScope.contains(parent)) {
                                 debug("adding git root for check: " + child.getPath());
                                 ((VcsModifiableDirtyScope)dirtyScope).addDirtyDirRecursively(VcsContextFactory.getInstance().createFilePathOn(child));
                               }
                               return true;
                             }
    );
  }

  /**
   * Common debug logging method for all Git status related operations.
   * Primarily used for measuring performance and tracking calls to heavy methods.
   */
  public static void debug(String message) {
    PROFILE_LOG.debug(message);
  }

  private static class MyNonChangedHolder {
    private final Project myProject;
    private final Set<FilePath> myDirty;
    private final ChangeListManagerGate myAddGate;
    private FileDocumentManager myFileDocumentManager;
    private ProjectLevelVcsManager myVcsManager;

    private MyNonChangedHolder(final Project project,
                               final Set<FilePath> dirty,
                               final ChangeListManagerGate addGate,
                               FileDocumentManager fileDocumentManager, ProjectLevelVcsManager vcsManager) {
      myProject = project;
      myDirty = new HashSet<>(dirty);
      myAddGate = addGate;
      myFileDocumentManager = fileDocumentManager;
      myVcsManager = vcsManager;
    }

    public void changed(final Collection<Change> changes) {
      for (Change change : changes) {
        final FilePath beforePath = ChangesUtil.getBeforePath(change);
        if (beforePath != null) {
          myDirty.remove(beforePath);
        }
        final FilePath afterPath = ChangesUtil.getBeforePath(change);
        if (afterPath != null) {
          myDirty.remove(afterPath);
        }
      }
    }

    public void unversioned(final VirtualFile vf) {
      // NB: There was an exception that happened several times: vf == null.
      // Populating myUnversioned in the ChangeCollector makes nulls not possible in myUnversioned,
      // so proposing that the exception was fixed.
      // More detailed analysis will be needed in case the exception appears again. 2010-12-09.
      myDirty.remove(VcsContextFactory.getInstance().createFilePathOn(vf));
    }

    public void feedBuilder(final ChangelistBuilder builder) throws VcsException {
      final VcsKey gitKey = GitVcs.getKey();

      for (FilePath filePath : myDirty) {
        final VirtualFile vf = filePath.getVirtualFile();
        if (vf != null) {
          if ((myAddGate.getStatus(vf) == null) && myFileDocumentManager.isFileModified(vf)) {
            final VirtualFile root = myVcsManager.getVcsRootFor(vf);
            if (root != null) {
              final GitRevisionNumber beforeRevisionNumber = GitChangeUtils.resolveReference(myProject, root, "HEAD");
              builder.processChange(new Change(GitContentRevision.createRevision(vf, beforeRevisionNumber, myProject),
                                               GitContentRevision.createRevision(vf, null, myProject), FileStatus.MODIFIED), gitKey);
            }
          }
        }
      }
    }
  }

  @Override
  public boolean isModifiedDocumentTrackingRequired() {
    return true;
  }

  @Override
  public void doCleanup(final List<VirtualFile> files) {
  }
}
