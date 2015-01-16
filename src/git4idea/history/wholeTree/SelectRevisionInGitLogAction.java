package git4idea.history.wholeTree;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.ui.VcsBalloonProblemNotifier;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.vcs.log.VcsLog;
import com.intellij.vcs.log.impl.VcsLogContentProvider;
import com.intellij.vcs.log.impl.VcsLogManager;
import com.intellij.vcs.log.ui.VcsLogUiImpl;
import git4idea.i18n.GitBundle;

public class SelectRevisionInGitLogAction extends DumbAwareAction {

  public SelectRevisionInGitLogAction() {
    super(GitBundle.message("vcs.history.action.gitlog"), GitBundle.message("vcs.history.action.gitlog"), null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    Project project = event.getRequiredData(CommonDataKeys.PROJECT);
    final VcsRevisionNumber revision = getRevisionNumber(event);
    if (revision == null) {
      return;
    }

    boolean logReady = findLog(project) != null;

    final ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(ChangesViewContentManager.TOOLWINDOW_ID);
    ContentManager cm = window.getContentManager();
    Content[] contents = cm.getContents();
    for (Content content : contents) {
      if (VcsLogContentProvider.TAB_NAME.equals(content.getDisplayName())) {
        cm.setSelectedContent(content);
        break;
      }
    }

    final VcsLog log = findLog(project);
    if (log == null) {
      showLogNotReadyMessage(project);
      return;
    }

    Runnable selectAndOpenLog = new Runnable() {
      @Override
      public void run() {
        Runnable selectCommit = new Runnable() {
          @Override
          public void run() {
            log.jumpToReference(revision.asString());
          }
        };

        if (!window.isVisible()) {
          window.activate(selectCommit, true);
        }
        else {
          selectCommit.run();
        }
      }
    };

    if (logReady) {
      selectAndOpenLog.run();
      return;
    }

    VcsLogManager logManager = VcsLogContentProvider.findLogManager(project);
    if (logManager == null) {
      showLogNotReadyMessage(project);
      return;
    }
    VcsLogUiImpl logUi = logManager.getLogUi();
    if (logUi == null) {
      showLogNotReadyMessage(project);
      return;
    }
    logUi.invokeOnChange(selectAndOpenLog);
  }

  private static void showLogNotReadyMessage(@NotNull Project project) {
    VcsBalloonProblemNotifier.showOverChangesView(project, GitBundle.message("vcs.history.action.gitlog.error"), MessageType.WARNING);
  }

  @Nullable
  private static VcsRevisionNumber getRevisionNumber(@NotNull AnActionEvent event) {
    VcsRevisionNumber revision = event.getData(VcsDataKeys.VCS_REVISION_NUMBER);
    if (revision == null) {
      VcsFileRevision fileRevision = event.getData(VcsDataKeys.VCS_FILE_REVISION);
      if (fileRevision != null) {
        revision = fileRevision.getRevisionNumber();
      }
    }
    return revision;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    super.update(e);
    e.getPresentation().setEnabled(e.getProject() != null &&
                                   VcsLogContentProvider.findLogManager(e.getProject()) != null &&
                                   (e.getData(VcsDataKeys.VCS_FILE_REVISION) != null ||
                                    e.getData(VcsDataKeys.VCS_REVISION_NUMBER) != null));
  }

  @Nullable
  private static VcsLog findLog(@NotNull Project project) {
    VcsLogManager manager = VcsLogContentProvider.findLogManager(project);
    if (manager != null) {
      VcsLogUiImpl ui = manager.getLogUi();
      if (ui != null) {
        return ui.getVcsLog();
      }
    }
    return null;
  }


}
