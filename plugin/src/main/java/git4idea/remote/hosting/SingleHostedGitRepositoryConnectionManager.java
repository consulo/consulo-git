// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.remote.hosting;

import consulo.accountManagement.Account;
import consulo.util.concurrent.coroutine.ObservableValue;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Hold a single repository connection
 * If a new connection is requested the old one should be closed first
 */
public interface SingleHostedGitRepositoryConnectionManager<M extends HostedGitRepositoryMapping, C extends HostedGitRepositoryConnection<M>> {
    ObservableValue<@Nullable C> getConnectionState();

    /**
     * Connect to {@code repo} with {@code account} and save the connection
     */
    CompletableFuture<@Nullable C> openConnection(M repo, Account account);

    /**
     * Close the current connection if any
     */
    CompletableFuture<?> closeConnection();
}
