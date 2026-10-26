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

import consulo.document.util.TextRange;
import consulo.project.Project;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.IssueNavigationConfiguration;

import java.util.List;

public final class GitBranchPresentation {
    public static final int BRANCH_NAME_LENGTH_DELTA = 4;
    public static final int BRANCH_NAME_SUFFIX_LENGTH = 5;

    private static final int DEFAULT_MAX_BRANCH_NAME_LENGTH = 80;

    private static final String ELLIPSIS = "…";

    private GitBranchPresentation() {
    }

    public static String truncateBranchName(Project project, String branchName) {
        return truncateBranchName(project, branchName, DEFAULT_MAX_BRANCH_NAME_LENGTH);
    }

    public static String truncateBranchName(Project project, String branchName, int maxBranchNameLength) {
        return truncateBranchName(project, branchName, maxBranchNameLength, BRANCH_NAME_SUFFIX_LENGTH, BRANCH_NAME_LENGTH_DELTA);
    }

    public static String truncateBranchName(Project project, String branchName, int maxBranchNameLength, int suffixLength, int delta) {
        int branchNameLength = branchName.length();

        if (branchNameLength <= maxBranchNameLength + delta) {
            return branchName;
        }

        IssueNavigationConfiguration issueNavigationConfiguration = IssueNavigationConfiguration.getInstance(project);
        List<IssueNavigationConfiguration.LinkMatch> issueMatches = issueNavigationConfiguration.findIssueLinks(branchName);
        int affectedMaxBranchNameLength = maxBranchNameLength - ELLIPSIS.length();
        if (!issueMatches.isEmpty()) {
            // never truncate the first occurrence of the issue id
            IssueNavigationConfiguration.LinkMatch firstMatch = issueMatches.get(0);
            TextRange firstMatchRange = firstMatch.getRange();
            return truncateAndSaveIssueId(firstMatchRange, branchName, affectedMaxBranchNameLength, suffixLength, delta);
        }

        if (affectedMaxBranchNameLength - suffixLength - ELLIPSIS.length() < 0) {
            return branchName;
        }

        return StringUtil.shortenTextWithEllipsis(branchName, affectedMaxBranchNameLength, suffixLength, true);
    }

    private static String truncateAndSaveIssueId(TextRange issueIdRange,
                                                 String branchName,
                                                 int maxBranchNameLength,
                                                 int suffixLength,
                                                 int delta) {
        String truncatedByDefault = StringUtil.shortenTextWithEllipsis(branchName, maxBranchNameLength, suffixLength, true);
        String issueId = issueIdRange.substring(branchName);

        if (truncatedByDefault.contains(issueId)) {
            return truncatedByDefault;
        }

        try {
            int branchNameLength = branchName.length();
            int endOffset = issueIdRange.getEndOffset();
            int startOffset = issueIdRange.getStartOffset();

            // suffix intersects with the issue id
            if (endOffset >= branchNameLength - suffixLength - delta) {
                return StringUtil.shortenTextWithEllipsis(branchName, maxBranchNameLength, branchNameLength - startOffset, true);
            }

            String suffix = branchName.substring(branchNameLength - suffixLength);
            int prefixLength = maxBranchNameLength - suffixLength - issueId.length();

            String prefixAndIssue;
            if (Math.abs(startOffset - prefixLength) <= delta) {
                prefixAndIssue = branchName.substring(0, endOffset);
            }
            else {
                String prefix = branchName.substring(0, prefixLength);
                prefixAndIssue = prefix + ELLIPSIS + issueId;
            }

            return prefixAndIssue + ELLIPSIS + suffix;
        }
        catch (Throwable e) {
            return truncatedByDefault;
        }
    }
}
