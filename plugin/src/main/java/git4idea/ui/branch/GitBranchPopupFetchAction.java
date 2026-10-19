// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/*
 * Copyright 2013-2026 consulo.io
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
package git4idea.ui.branch;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionParentRef;
import consulo.annotation.component.ActionRef;
import consulo.annotation.component.ActionRefAnchor;
import consulo.git.icon.GitIconGroup;
import git4idea.actions.GitFetch;

/**
 * Port of {@code git4idea.ui.branch.GitBranchPopupFetchAction}: the fetch action shown in the
 * branches popup header toolbar.
 * <p>
 * JB uses {@code AllIcons.Vcs.Fetch} and swaps in an animated icon while a fetch is running (via
 * {@code GitFetchSupport.isFetchRunning}). Consulo has no {@code GitFetchSupport}, so the
 * busy-state animation is omitted, but the same JB {@code Vcs.Fetch} glyph is used (ported into
 * {@link GitIconGroup#fetch()}).
 *
 * @author VISTALL
 */
@ActionImpl(
    id = "Git.Log.Branches.Fetch",
    parents = @ActionParentRef(
        value = @ActionRef(id = "Dvcs.Log.Branches.Toolbar"),
        anchor = ActionRefAnchor.AFTER,
        relatedToAction = @ActionRef(id = "Dvcs.Log.Branches.Checkout")
    )
)
public class GitBranchPopupFetchAction extends GitFetch {
    public GitBranchPopupFetchAction() {
        getTemplatePresentation().setIcon(GitIconGroup.fetch());
    }
}
