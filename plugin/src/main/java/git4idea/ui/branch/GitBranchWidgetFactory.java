package git4idea.ui.branch;

import consulo.annotation.component.ExtensionImpl;
import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.project.ui.wm.StatusBar;
import consulo.project.ui.wm.StatusBarWidget;
import consulo.project.ui.wm.StatusBarWidgetFactory;
import jakarta.annotation.Nonnull;

@ExtensionImpl(id = "gitWidget", order = "after codeStyleWidget,before readOnlyWidget")
public class GitBranchWidgetFactory implements StatusBarWidgetFactory {
    @Override
    @Nonnull
    public String getDisplayName() {
        return GitLocalize.gitStatusBarWidgetName().get();
    }

    @Override
    public boolean isAvailable(@Nonnull Project project) {
        //return !GitRepositoryManager.getInstance(project).getRepositories().isEmpty();
        return true;
    }

    @Override
    @Nonnull
    public StatusBarWidget createWidget(@Nonnull Project project) {
        return new GitBranchWidget(project, this);
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public boolean canBeEnabledOn(@Nonnull StatusBar statusBar) {
        return true;
    }
}
