/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package git4idea.commands;

import jakarta.annotation.Nonnull;
import consulo.util.dataholder.Key;
import consulo.util.lang.StringUtil;

/**
 * @author Kirill Likhodedov
 */
public class GitSimpleEventDetector implements GitLineHandlerListener
{

	@Nonnull
	private final Event myEvent;
	private boolean myHappened;

	public enum Event
	{
		CHERRY_PICK_CONFLICT("after resolving the conflicts"),
		LOCAL_CHANGES_OVERWRITTEN_BY_CHERRY_PICK("would be overwritten by merge"),
		UNMERGED_PREVENTING_CHECKOUT("you need to resolve your current index first"),
		UNMERGED_PREVENTING_MERGE("is not possible because you have unmerged files"),
		BRANCH_NOT_FULLY_MERGED("is not fully merged"),
		MERGE_CONFLICT("Automatic merge failed; fix conflicts and then commit the result"),
		MERGE_CONFLICT_ON_UNSTASH("conflict"),
		ALREADY_UP_TO_DATE("Already up-to-date"),
		INVALID_REFERENCE("invalid reference:");

		private final String myDetectionString;

		Event(@Nonnull String detectionString)
		{
			myDetectionString = detectionString;
		}
	}

	public GitSimpleEventDetector(@Nonnull Event event)
	{
		myEvent = event;
	}

	@Override
	public void onLineAvailable(@Nonnull String line, Key outputType)
	{
		if(StringUtil.containsIgnoreCase(line, myEvent.myDetectionString))
		{
			myHappened = true;
		}
	}

	@Override
	public void processTerminated(int exitCode)
	{
	}

	@Override
	public void startFailed(Throwable exception)
	{
	}

	public boolean hasHappened()
	{
		return myHappened;
	}

}
