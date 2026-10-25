// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote.hosting.ui;

import consulo.accountManagement.Account;
import consulo.util.concurrent.coroutine.MutableObservableValue;
import consulo.util.concurrent.coroutine.ObservableValue;
import git4idea.remote.hosting.HostedGitRepositoryMapping;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

public interface RepositoryAndAccountSelectorViewModel<M extends HostedGitRepositoryMapping> {
    ObservableValue<Set<M>> getRepositoriesState();

    MutableObservableValue<@Nullable M> getRepoSelectionState();

    ObservableValue<List<Account>> getAccountsState();

    MutableObservableValue<@Nullable Account> getAccountSelectionState();

    default ObservableValue<Boolean> getCanPersistCredentials() {
        return ObservableValue.of(true);
    }

    ObservableValue<@Nullable Error> getErrorState();

    ObservableValue<@Nullable Boolean> getMissingCredentialsState();

    ObservableValue<Boolean> getBusyState();

    ObservableValue<Boolean> getSubmitAvailableState();

    void submitSelection();

    void selectRepoAndAccount(M projectMapping, Account account);

    sealed interface Error permits SubmissionError, MissingCredentials {
    }

    record SubmissionError(HostedGitRepositoryMapping repo, Account account, Throwable exception) implements Error {
    }

    enum MissingCredentials implements Error {
        INSTANCE
    }
}
