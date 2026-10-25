// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.push;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.ui.ex.action.AnAction;
import consulo.versionControlSystem.distributed.push.VcsPushOptionValue;
import git4idea.repo.GitRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@ExtensionAPI(ComponentScope.PROJECT)
public interface GitPushNotificationCustomizer {
    CompletableFuture<List<AnAction>> getActions(GitRepository repository,
                                                 GitPushRepoResult pushResult,
                                                 Map<String, VcsPushOptionValue> customParams);
}
