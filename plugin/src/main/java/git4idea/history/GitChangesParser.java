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
package git4idea.history;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import jakarta.annotation.Nonnull;

import consulo.versionControlSystem.FilePath;
import consulo.virtualFileSystem.status.FileStatus;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ContentRevision;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.virtualFileSystem.VirtualFile;
import consulo.project.Project;
import git4idea.GitContentRevision;
import git4idea.GitRevisionNumber;
import git4idea.history.wholeTree.AbstractHash;
import jakarta.annotation.Nullable;

public class GitChangesParser
{

	@Nonnull
	public static List<Change> parse(
			@Nonnull Project project,
			@Nonnull VirtualFile root,
			@Nonnull List<GitLogStatusInfo> statusInfos,
			@Nonnull String hash,
			@Nonnull Date date,
			@Nonnull List<String> parentsHashes) throws VcsException
	{
		GitRevisionNumber thisRevision = new GitRevisionNumber(hash, date);
		List<GitRevisionNumber> parentRevisions = prepareParentRevisions(parentsHashes);

		List<Change> result = new ArrayList<Change>();
		for(GitLogStatusInfo statusInfo : statusInfos)
		{
			result.add(parseChange(project, root, parentRevisions, statusInfo, thisRevision));
		}
		return result;
	}

	private static List<GitRevisionNumber> prepareParentRevisions(List<String> parentsHashes)
	{
		final List<AbstractHash> parents = new ArrayList<AbstractHash>(parentsHashes.size());
		for(String parentsShortHash : parentsHashes)
		{
			parents.add(AbstractHash.create(parentsShortHash));
		}

		final List<GitRevisionNumber> parentRevisions = new ArrayList<GitRevisionNumber>(parents.size());
		for(AbstractHash parent : parents)
		{
			parentRevisions.add(new GitRevisionNumber(parent.getString()));
		}
		return parentRevisions;
	}

	private static Change parseChange(
			final Project project,
			final VirtualFile vcsRoot,
			final List<GitRevisionNumber> parentRevisions,
			final GitLogStatusInfo statusInfo,
			final VcsRevisionNumber thisRevision) throws VcsException
	{
		final ContentRevision before;
		final ContentRevision after;
		FileStatus status = null;
		final String path = statusInfo.getFirstPath();
		@Nullable GitRevisionNumber firstParent = parentRevisions.isEmpty() ? null : parentRevisions.get(0);

		switch(statusInfo.getType())
		{
			case ADDED:
				before = null;
				status = FileStatus.ADDED;
				after = GitContentRevision.createRevision(vcsRoot, path, thisRevision, project, false, false, true);
				break;
			case UNRESOLVED:
				status = FileStatus.MERGED_WITH_CONFLICTS;
			case MODIFIED:
				if(status == null)
				{
					status = FileStatus.MODIFIED;
				}
				final FilePath filePath = GitContentRevision.createPath(vcsRoot, path, false, true, true);
				before = GitContentRevision.createRevision(vcsRoot, path, firstParent, project, false, false, true);
				after = GitContentRevision.createRevision(filePath, thisRevision, project, null);
				break;
			case DELETED:
				status = FileStatus.DELETED;
				final FilePath filePathDeleted = GitContentRevision.createPath(vcsRoot, path, true, true, true);
				before = GitContentRevision.createRevision(filePathDeleted, firstParent, project, null);
				after = null;
				break;
			case COPIED:
			case RENAMED:
				status = FileStatus.MODIFIED;
				String secondPath = statusInfo.getSecondPath();
				final FilePath filePathAfterRename = GitContentRevision.createPath(vcsRoot, secondPath == null ? path : secondPath, false, false,
						true);
				before = GitContentRevision.createRevision(vcsRoot, path, firstParent, project, true, true, true);
				after = GitContentRevision.createRevision(filePathAfterRename, thisRevision, project, null);
				break;
			case TYPE_CHANGED:
				status = FileStatus.MODIFIED;
				final FilePath filePath2 = GitContentRevision.createPath(vcsRoot, path, false, true, true);
				before = GitContentRevision.createRevision(vcsRoot, path, firstParent, project, false, false, true);
				after = GitContentRevision.createRevision(filePath2, thisRevision, project, null);
				break;
			default:
				throw new AssertionError("Unknown file status: " + statusInfo);
		}
		return new Change(before, after, status);
	}

}
