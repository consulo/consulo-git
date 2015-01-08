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
package git4idea;

import java.util.Collection;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.VcsListener;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcs.log.VcsLogObjectsFactory;
import com.intellij.vcs.log.VcsUser;
import git4idea.config.GitConfigUtil;

public class GitUserRegistry implements Disposable, VcsListener
{

	private static final Logger LOG = Logger.getInstance(GitUserRegistry.class);

	@NotNull
	private final Project myProject;
	@NotNull
	private final ProjectLevelVcsManager myVcsManager;
	@NotNull
	private final Map<VirtualFile, VcsUser> myUserMap = ContainerUtil.newConcurrentMap();

	public GitUserRegistry(@NotNull Project project, @NotNull ProjectLevelVcsManager vcsManager)
	{
		myProject = project;
		myVcsManager = vcsManager;
		Disposer.register(myProject, this);
	}

	public void activate()
	{
		myProject.getMessageBus().connect().subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, this);
		directoryMappingChanged();
	}

	@Nullable
	public VcsUser getUser(@NotNull VirtualFile root)
	{
		return myUserMap.get(root);
	}

	@Nullable
	public VcsUser getOrReadUser(@NotNull VirtualFile root)
	{
		VcsUser user = myUserMap.get(root);
		if(user == null)
		{
			try
			{
				user = readCurrentUser(myProject, root);
				if(user != null)
				{
					myUserMap.put(root, user);
				}
			}
			catch(VcsException e)
			{
				LOG.warn("Could not retrieve user name in " + root, e);
			}
		}
		return user;
	}

	@Nullable
	private static VcsUser readCurrentUser(@NotNull Project project, @NotNull VirtualFile root) throws VcsException
	{
		String userName = GitConfigUtil.getValue(project, root, GitConfigUtil.USER_NAME);
		String userEmail = StringUtil.notNullize(GitConfigUtil.getValue(project, root, GitConfigUtil.USER_EMAIL));
		VcsLogObjectsFactory factory = ServiceManager.getService(project, VcsLogObjectsFactory.class);
		return userName == null ? null : factory.createUser(userName, userEmail);
	}

	@Override
	public void dispose()
	{
		myUserMap.clear();
	}

	@Override
	public void directoryMappingChanged()
	{
		GitVcs vcs = GitVcs.getInstance(myProject);
		if(vcs == null)
		{
			return;
		}
		final VirtualFile[] roots = myVcsManager.getRootsUnderVcs(vcs);
		final Collection<VirtualFile> rootsToCheck = ContainerUtil.filter(roots, new Condition<VirtualFile>()
		{
			@Override
			public boolean value(VirtualFile root)
			{
				return getUser(root) == null;
			}
		});
		if(!rootsToCheck.isEmpty())
		{
			ApplicationManager.getApplication().executeOnPooledThread(new Runnable()
			{
				public void run()
				{
					for(VirtualFile root : rootsToCheck)
					{
						getOrReadUser(root);
					}
				}
			});
		}
	}

}