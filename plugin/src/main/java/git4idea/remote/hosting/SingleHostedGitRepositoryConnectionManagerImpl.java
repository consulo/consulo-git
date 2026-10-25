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
import consulo.disposer.Disposable;
import consulo.util.concurrent.coroutine.MutableObservableValue;
import consulo.util.concurrent.coroutine.ObservableValue;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class SingleHostedGitRepositoryConnectionManagerImpl<M extends HostedGitRepositoryMapping, C extends HostedGitRepositoryConnection<M>>
    implements SingleHostedGitRepositoryConnectionManager<M, C> {
    private final Disposable myParentDisposable;
    private final HostedGitRepositoryConnectionFactory<M, C> myConnectionFactory;

    private final MutableObservableValue<@Nullable C> myConnectionState = MutableObservableValue.of(null);
    private CompletableFuture<?> myConnectionStateGuard = CompletableFuture.completedFuture(null);

    public SingleHostedGitRepositoryConnectionManagerImpl(Disposable parentDisposable, HostedGitRepositoryConnectionFactory<M, C> connectionFactory) {
        myParentDisposable = parentDisposable;
        myConnectionFactory = connectionFactory;
    }

    @Override
    public ObservableValue<@Nullable C> getConnectionState() {
        return myConnectionState;
    }

    @Override
    public CompletableFuture<@Nullable C> openConnection(M repo, Account account) {
        return withLock(() -> {
            C current = myConnectionState.get();
            if (current == null || !current.getRepo().equals(repo) || !current.getAccount().getId().equals(account.getId())) {
                CompletableFuture<?> closed = current == null ? CompletableFuture.completedFuture(null) : current.close();
                return closed.thenCompose(ignored -> myConnectionFactory.connect(myParentDisposable, repo, account)).thenApply(connection -> {
                    myConnectionState.set(connection);
                    return myConnectionState.get();
                });
            }
            return CompletableFuture.completedFuture(myConnectionState.get());
        });
    }

    @Override
    public CompletableFuture<?> closeConnection() {
        return withLock(() -> {
            C current = myConnectionState.get();
            CompletableFuture<?> closed = current == null ? CompletableFuture.completedFuture(null) : current.close();
            return closed.thenApply(ignored -> {
                myConnectionState.set(null);
                return null;
            });
        });
    }

    private synchronized <T extends @Nullable Object> CompletableFuture<T> withLock(Supplier<CompletableFuture<T>> action) {
        CompletableFuture<T> result = myConnectionStateGuard.handle((ignored, error) -> null).thenCompose(ignored -> action.get());
        myConnectionStateGuard = result;
        return result;
    }
}
