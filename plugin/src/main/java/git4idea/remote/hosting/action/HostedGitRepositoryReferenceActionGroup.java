// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote.hosting.action;

import consulo.application.dumb.DumbAware;
import consulo.dataContext.DataContext;
import consulo.localize.LocalizeValue;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.ActionGroup;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.ex.action.Presentation;
import consulo.ui.image.Image;
import org.jspecify.annotations.Nullable;

import java.util.List;

public abstract class HostedGitRepositoryReferenceActionGroup extends ActionGroup implements DumbAware, AnActionWithSyncUpdate {
    protected HostedGitRepositoryReferenceActionGroup() {
        super();
    }

    protected HostedGitRepositoryReferenceActionGroup(LocalizeValue dynamicText, LocalizeValue dynamicDescription, @Nullable Image icon) {
        super(dynamicText, dynamicDescription, icon);
    }

    @Override
    public void update(AnActionEvent e) {
        List<HostedGitRepositoryReference> references = distinctReferences(e.getDataContext());
        Presentation presentation = e.getPresentation();
        presentation.setEnabledAndVisible(!references.isEmpty());
        presentation.setPerformGroup(references.size() == 1);
        presentation.setPopupGroup(true);
    }

    @Override
    public boolean disableIfNoVisibleChildren() {
        return false;
    }

    @Override
    public AnAction[] getChildren(@Nullable AnActionEvent e) {
        if (e == null) {
            return AnAction.EMPTY_ARRAY;
        }
        List<HostedGitRepositoryReference> references = distinctReferences(e.getDataContext());
        if (references.size() <= 1) {
            return AnAction.EMPTY_ARRAY;
        }

        return references.stream().map(OpenInBrowserAction::new).toArray(AnAction[]::new);
    }

    @RequiredUIAccess
    @Override
    public void actionPerformed(AnActionEvent e) {
        List<HostedGitRepositoryReference> references = findReferences(e.getDataContext());
        if (references.isEmpty()) {
            return;
        }
        new OpenInBrowserAction(references.getFirst()).actionPerformed(e);
    }

    private List<HostedGitRepositoryReference> distinctReferences(DataContext dataContext) {
        return findReferences(dataContext).stream().distinct().toList();
    }

    protected abstract List<HostedGitRepositoryReference> findReferences(DataContext dataContext);

    protected abstract void handleReference(HostedGitRepositoryReference reference);

    private class OpenInBrowserAction extends DumbAwareAction {
        private final HostedGitRepositoryReference myReference;

        OpenInBrowserAction(HostedGitRepositoryReference reference) {
            super(reference.getName());
            myReference = reference;
        }

        @RequiredUIAccess
        @Override
        public void actionPerformed(AnActionEvent e) {
            handleReference(myReference);
        }
    }
}
