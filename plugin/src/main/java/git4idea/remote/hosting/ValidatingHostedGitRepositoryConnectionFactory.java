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
import consulo.accountManagement.AccountConnection;
import consulo.accountManagement.AccountListener;
import consulo.accountManagement.AccountManager;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.concurrent.coroutine.MutableObservableValue;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Creates a self-validating connection which is automatically closed when repo, account ort credentials are missing
 */
public final class ValidatingHostedGitRepositoryConnectionFactory<M extends HostedGitRepositoryMapping, C extends HostedGitRepositoryConnection<M>>
    implements HostedGitRepositoryConnectionFactory<M, C> {
    private static final Logger LOG = Logger.getInstance(ValidatingHostedGitRepositoryConnectionFactory.class);

    private final Supplier<HostedGitRepositoriesManager<M>> myRepositoriesManager;
    private final Supplier<AccountManager> myAccountManager;
    private final @Nullable Project myProject;
    private final HostedGitRepositoryConnectionCreator<M, C> myCreateConnection;

    public ValidatingHostedGitRepositoryConnectionFactory(Supplier<HostedGitRepositoriesManager<M>> repositoriesManager,
                                                          Supplier<AccountManager> accountManager,
                                                          @Nullable Project project,
                                                          HostedGitRepositoryConnectionCreator<M, C> createConnection) {
        myRepositoriesManager = repositoriesManager;
        myAccountManager = accountManager;
        myProject = project;
        myCreateConnection = createConnection;
    }

    @Override
    public CompletableFuture<C> connect(Disposable parentDisposable, M repo, Account account) {
        Disposable connectionDisposable = Disposable.newDisposable("HostedGitRepositoryConnection " + repo);
        Disposer.register(parentDisposable, connectionDisposable);

        AccountManager accountManager = myAccountManager.get();
        return accountManager.connect(account).thenCompose(connection -> {
            MutableObservableValue<AccountConnection> requiredCredentialsState = MutableObservableValue.of(connection);

            accountManager.addListener(new AccountListener() {
                @Override
                public void accountsChanged() {
                    if (accountManager.findAccount(account.getId(), myProject) == null) {
                        fail(connectionDisposable, "Account " + account.getName() + " is missing");
                    }
                }

                @Override
                public void accountChanged(Account changed) {
                    if (!changed.getId().equals(account.getId()) || changed.isAuthFailed()) {
                        return;
                    }
                    accountManager.connect(changed).whenComplete((newConnection, error) -> {
                        if (error != null) {
                            fail(connectionDisposable, "Token for account " + account.getName() + " is missing");
                        }
                        else {
                            requiredCredentialsState.set(newConnection);
                        }
                    });
                }
            }, connectionDisposable);

            HostedGitRepositoriesManager<M> repositoriesManager = myRepositoriesManager.get();
            Runnable checkRepository = () -> {
                Set<M> repositories = repositoriesManager.getKnownRepositoriesState().get();
                if (!repositories.contains(repo)) {
                    fail(connectionDisposable, "Repository mapping " + repo + " is missing");
                }
            };
            Disposer.register(connectionDisposable, repositoriesManager.getKnownRepositoriesState().addListener(it -> checkRepository.run())::run);
            checkRepository.run();

            if (Disposer.isDisposed(connectionDisposable)) {
                return CompletableFuture.failedFuture(new IllegalStateException("Connection to " + repo + " is closed"));
            }
            return myCreateConnection.create(connectionDisposable, repo, account, requiredCredentialsState);
        }).whenComplete((result, error) -> {
            if (error != null) {
                fail(connectionDisposable, error.getLocalizedMessage());
            }
        });
    }

    private static void fail(Disposable connectionDisposable, @Nullable String message) {
        if (message != null) {
            LOG.info(message);
        }
        Disposer.dispose(connectionDisposable);
    }
}
