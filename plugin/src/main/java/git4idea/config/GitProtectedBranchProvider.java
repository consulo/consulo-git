// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.config;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.project.Project;

import java.util.ArrayList;
import java.util.List;

@ExtensionAPI(ComponentScope.APPLICATION)
public interface GitProtectedBranchProvider {
    static List<String> getProtectedBranchPatterns(Project project) {
        List<String> patterns = new ArrayList<>();
        project.getApplication().getExtensionPoint(GitProtectedBranchProvider.class)
            .forEach(provider -> patterns.addAll(provider.doGetProtectedBranchPatterns(project)));
        return patterns;
    }

    /**
     * Return protected branch patterns in regex format.
     */
    List<String> doGetProtectedBranchPatterns(Project project);
}
