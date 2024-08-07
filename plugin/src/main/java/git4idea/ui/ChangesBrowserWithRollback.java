/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package git4idea.ui;

import consulo.ide.impl.idea.openapi.vcs.changes.actions.RollbackDialogAction;
import consulo.ide.impl.idea.openapi.vcs.changes.ui.ChangesBrowser;
import consulo.project.Project;
import consulo.ui.ex.action.EmptyAction;
import consulo.ui.ex.action.IdeActions;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.function.Condition;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangeListManager;
import jakarta.annotation.Nonnull;

import java.util.Collection;
import java.util.List;

/**
 * {@link ChangesBrowser} extension with Rollback/Revert action added to the toolbar.
 * After the revert completes, the changes list is automatically refreshed according to the actual changes
 * retrieved from the {@link ChangeListManager}.
 */
public class ChangesBrowserWithRollback extends ChangesBrowser
{
	private final List<Change> myOriginalChanges;

	public ChangesBrowserWithRollback(@Nonnull Project project, @Nonnull List<Change> changes)
	{
		super(project, null, changes, null, false, true, null, MyUseCase.LOCAL_CHANGES, null);
		myOriginalChanges = changes;
		RollbackDialogAction rollback = new RollbackDialogAction();
		EmptyAction.setupAction(rollback, IdeActions.CHANGES_VIEW_ROLLBACK, this);
		addToolbarAction(rollback);
		setChangesToDisplay(changes);
	}

	@Override
	public void rebuildList()
	{
		if(myOriginalChanges != null)
		{ // null is possible because rebuildList is called during initialization
			myChangesToDisplay = filterActualChanges(myProject, myOriginalChanges);
		}
		super.rebuildList();
	}

	@Nonnull
	private static List<Change> filterActualChanges(@Nonnull Project project, @Nonnull List<Change> originalChanges)
	{
		final Collection<Change> allChanges = ChangeListManager.getInstance(project).getAllChanges();
		return ContainerUtil.filter(originalChanges, new Condition<Change>()
		{
			@Override
			public boolean value(Change change)
			{
				return allChanges.contains(change);
			}
		});
	}
}
