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
package git4idea.ui.branch.popup;

import consulo.localize.LocalizeValue;
import consulo.versionControlSystem.distributed.ui.branch.popup.DvcsRefType;
import git4idea.branch.GitBranchType;

/**
 * Git-specific {@link DvcsRefType} values shown in the branches tree.
 * <p>
 * Only branch groups are supported for now, since this consulo-git model does not load tags into
 * {@code GitRepository}. TAG can be added once a tag holder exists.
 *
 * @author VISTALL
 */
public enum GitRefType implements DvcsRefType {
    LOCAL(GitBranchType.LOCAL, "Local", "Local Branches", "Common Local Branches"),
    REMOTE(GitBranchType.REMOTE, "Remote", "Remote Branches", "Common Remote Branches");

    private final GitBranchType myBranchType;
    private final String myText;
    private final String myInRepoText;
    private final String myCommonText;

    GitRefType(GitBranchType branchType, String text, String inRepoText, String commonText) {
        myBranchType = branchType;
        myText = text;
        myInRepoText = inRepoText;
        myCommonText = commonText;
    }

    public GitBranchType getBranchType() {
        return myBranchType;
    }

    @Override
    public String getName() {
        return name();
    }

    @Override
    public LocalizeValue getText() {
        return LocalizeValue.localizeTODO(myText);
    }

    @Override
    public LocalizeValue getInRepoText(String repositoryName) {
        return LocalizeValue.localizeTODO(myInRepoText + " in " + repositoryName);
    }

    @Override
    public LocalizeValue getCommonText() {
        return LocalizeValue.localizeTODO(myCommonText);
    }
}
