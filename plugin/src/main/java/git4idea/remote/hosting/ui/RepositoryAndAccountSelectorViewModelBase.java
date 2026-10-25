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
package git4idea.remote.hosting.ui;

import consulo.accountManagement.Account;
import consulo.accountManagement.AccountListener;
import consulo.accountManagement.AccountManager;
import consulo.accountManagement.AccountType;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.http.client.URIUtil;
import consulo.project.Project;
import consulo.util.concurrent.coroutine.MutableObservableValue;
import consulo.util.concurrent.coroutine.ObservableValue;
import git4idea.remote.hosting.HostedGitRepositoriesManager;
import git4idea.remote.hosting.HostedGitRepositoryMapping;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

public abstract class RepositoryAndAccountSelectorViewModelBase<M extends HostedGitRepositoryMapping>
    implements RepositoryAndAccountSelectorViewModel<M> {

    private final Project myProject;
    private final AccountManager myAccountManager;
    private final AccountType<?> myAccountType;
    private final BiFunction<M, Account, CompletableFuture<?>> myOnSelected;

    private final ObservableValue<Set<M>> myRepositoriesState;
    private final MutableObservableValue<@Nullable M> myRepoSelectionState = MutableObservableValue.of(null);
    private final MutableObservableValue<List<Account>> myAccountsState = MutableObservableValue.of(List.of());
    private final MutableObservableValue<@Nullable Account> myAccountSelectionState = MutableObservableValue.of(null);
    private final MutableObservableValue<@Nullable Boolean> myMissingCredentialsState = MutableObservableValue.of(null);
    private final MutableObservableValue<@Nullable SubmissionError> mySubmissionErrorState = MutableObservableValue.of(null);
    private final MutableObservableValue<@Nullable Error> myErrorState = MutableObservableValue.of(null);
    private final MutableObservableValue<Boolean> mySubmittingState = MutableObservableValue.of(false);
    private final MutableObservableValue<Boolean> myBusyState = MutableObservableValue.of(true);
    private final MutableObservableValue<Boolean> mySubmitAvailableState = MutableObservableValue.of(false);

    protected RepositoryAndAccountSelectorViewModelBase(Disposable parentDisposable,
                                                        Project project,
                                                        HostedGitRepositoriesManager<M> repositoriesManager,
                                                        AccountManager accountManager,
                                                        AccountType<?> accountType,
                                                        BiFunction<M, Account, CompletableFuture<?>> onSelected) {
        myProject = project;
        myAccountManager = accountManager;
        myAccountType = accountType;
        myOnSelected = onSelected;
        myRepositoriesState = repositoriesManager.getKnownRepositoriesState();

        Disposer.register(parentDisposable, myRepoSelectionState.addListener(repo -> {
            updateAccounts();
            updateDerivedStates();
        })::run);
        Disposer.register(parentDisposable, myAccountSelectionState.addListener(account -> {
            updateMissingCredentials();
            updateDerivedStates();
        })::run);
        Disposer.register(parentDisposable, myMissingCredentialsState.addListener(missing -> updateDerivedStates())::run);
        Disposer.register(parentDisposable, mySubmissionErrorState.addListener(error -> updateDerivedStates())::run);
        Disposer.register(parentDisposable, mySubmittingState.addListener(submitting -> updateDerivedStates())::run);
        accountManager.addListener(new AccountListener() {
            @Override
            public void accountsChanged() {
                updateAccounts();
                updateMissingCredentials();
            }

            @Override
            public void accountChanged(Account account) {
                updateAccounts();
                updateMissingCredentials();
            }
        }, parentDisposable);

        updateAccounts();
        updateMissingCredentials();
        updateDerivedStates();
    }

    private void updateAccounts() {
        M repo = myRepoSelectionState.get();
        if (repo == null) {
            myAccountsState.set(List.of());
            return;
        }
        URI repoServerUri = repo.getRepository().getServerPath().toURI();
        List<Account> accounts = new ArrayList<>();
        for (Account account : myAccountManager.getAccounts(myAccountType, myProject)) {
            URI accountServerUri = toUri(myAccountManager.getServerUrl(account));
            if (accountServerUri != null && URIUtil.equalWithoutSchema(accountServerUri, repoServerUri)) {
                accounts.add(account);
            }
        }
        myAccountsState.set(List.copyOf(accounts));
    }

    private void updateMissingCredentials() {
        Account selected = myAccountSelectionState.get();
        if (selected == null) {
            myMissingCredentialsState.set(false);
            return;
        }
        Account account = myAccountManager.findAccount(selected.getId(), myProject);
        myMissingCredentialsState.set(account == null || account.isAuthFailed());
    }

    private void updateDerivedStates() {
        Boolean missingCredentials = myMissingCredentialsState.get();
        myErrorState.set(Objects.equals(missingCredentials, Boolean.TRUE) ? MissingCredentials.INSTANCE : mySubmissionErrorState.get());
        myBusyState.set(missingCredentials == null || mySubmittingState.get());
        mySubmitAvailableState.set(myRepoSelectionState.get() != null
            && myAccountSelectionState.get() != null
            && Objects.equals(missingCredentials, Boolean.FALSE));
    }

    private static @Nullable URI toUri(@Nullable String url) {
        if (url == null) {
            return null;
        }
        try {
            return new URI(url);
        }
        catch (URISyntaxException e) {
            return null;
        }
    }

    @Override
    public final ObservableValue<Set<M>> getRepositoriesState() {
        return myRepositoriesState;
    }

    @Override
    public final MutableObservableValue<@Nullable M> getRepoSelectionState() {
        return myRepoSelectionState;
    }

    @Override
    public final ObservableValue<List<Account>> getAccountsState() {
        return myAccountsState;
    }

    @Override
    public final MutableObservableValue<@Nullable Account> getAccountSelectionState() {
        return myAccountSelectionState;
    }

    @Override
    public final ObservableValue<@Nullable Boolean> getMissingCredentialsState() {
        return myMissingCredentialsState;
    }

    @Override
    public final ObservableValue<@Nullable Error> getErrorState() {
        return myErrorState;
    }

    @Override
    public final ObservableValue<Boolean> getBusyState() {
        return myBusyState;
    }

    @Override
    public final ObservableValue<Boolean> getSubmitAvailableState() {
        return mySubmitAvailableState;
    }

    @Override
    public void submitSelection() {
        M repo = myRepoSelectionState.get();
        if (repo == null) {
            return;
        }
        Account account = myAccountSelectionState.get();
        if (account == null) {
            return;
        }
        mySubmittingState.set(true);
        CompletableFuture<?> future;
        try {
            future = myOnSelected.apply(repo, account);
        }
        catch (Throwable e) {
            mySubmissionErrorState.set(new SubmissionError(repo, account, e));
            mySubmittingState.set(false);
            return;
        }
        future.whenComplete((result, error) -> {
            if (error == null) {
                mySubmissionErrorState.set(null);
            }
            else if (!(error instanceof CancellationException)) {
                mySubmissionErrorState.set(new SubmissionError(repo, account, error));
            }
            mySubmittingState.set(false);
        });
    }

    @Override
    public void selectRepoAndAccount(M projectMapping, Account account) {
        myRepoSelectionState.set(projectMapping);
        myAccountSelectionState.set(account);
    }
}
