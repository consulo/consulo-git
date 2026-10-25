// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.remote.hosting.http;

import consulo.accountManagement.Account;
import consulo.accountManagement.AccountBasicCredentials;
import consulo.accountManagement.AccountConnection;
import consulo.accountManagement.AccountManager;
import consulo.accountManagement.AccountType;
import consulo.credentialStorage.AuthData;
import consulo.logging.Logger;
import consulo.project.Project;
import git4idea.remote.GitHttpAuthDataProvider;
import git4idea.remote.hosting.GitHostingUrlUtil;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Base class that provides a business logic part of {@link GitHttpAuthDataProvider} interface,
 * that can be extended by git hosting providers such as Space, GitHub, GitLab, BitBucket, etc.
 * <p>
 * Will do no user interaction
 * <p>
 * This logic can be described as: if user is logged in plugin (so their account is stored in {@link #getAccountManager()})
 * then their token will be passed as password for http remote operations.
 * <p>
 * If user is logged in more than one account and user has chosen the default account (see {@link #getDefaultAccount})
 * then this default account's details will be used.
 * <p>
 * If authorization with given credentials failed, account will be stored to {@link HostedGitAuthenticationFailureManager}
 * and not used in the future attempts (until account token change or project reopen)
 * <p>
 * Clients' auth data providers should be registered as {@link GitHttpAuthDataProvider} extensions.
 */
public abstract class SilentHostedGitHttpAuthDataProviderBase implements GitHttpAuthDataProvider {
    private static final Logger LOG = Logger.getInstance(SilentHostedGitHttpAuthDataProviderBase.class);

    protected abstract String getProviderId();

    /**
     * Account manager that holds accounts and their credentials.
     */
    protected abstract AccountManager getAccountManager();

    /**
     * Type of the accounts that are used for the git remotes of this hosting.
     */
    protected abstract AccountType<?> getAccountType();

    /**
     * Holder for accounts and their creds that failed to access git remotes operations.
     * Such accounts won't be provided to git http remote operations until creds changed or IDE restarted.
     * <p>
     * In common, clients should implement their own project service to keep such accounts.
     */
    protected abstract HostedGitAuthenticationFailureManager getAuthFailureManager(Project project);

    /**
     * Provides the default account selected by user.
     * So that if there are more than one account in {@link #getAccountManager()},
     * then this account will be used as login and credentials provider
     */
    protected @Nullable Account getDefaultAccount(Project project) {
        return getAccountManager().getActiveAccount(getAccountType(), project);
    }

    /**
     * Provides the login and password that will be passed to git remote http operation.
     * Or {@code null} if login or password cannot be acquired, in this case nothing will be passed to git http remote operations and
     * user will be asked to input the data themselves.
     */
    protected CompletableFuture<@Nullable AuthData> getAuthData(Account account) {
        return getAccountManager().connect(account).thenApply(connection -> toAuthData(account, connection));
    }

    @Override
    public final boolean isSilent() {
        return true;
    }

    @Override
    public final @Nullable AuthData getAuthData(Project project, String url) {
        AuthData defaultAuthData = getDefaultAccountData(project, url);
        if (defaultAuthData != null) {
            return defaultAuthData;
        }

        Collection<Account> accounts = getAccounts(project, url);
        if (accounts.size() != 1) {
            return null;
        }
        Account account = accounts.iterator().next();
        AuthData authData = await(getAuthData(account));
        return authData == null ? null : new AccountAuthData(account, authData, getProviderId());
    }

    @Override
    public final @Nullable AuthData getAuthData(Project project, String url, String login) {
        AccountAuthData defaultAuthData = getDefaultAccountData(project, url);
        if (defaultAuthData != null && defaultAuthData.getLogin().equals(login)) {
            return defaultAuthData;
        }

        List<AccountAuthData> result = new ArrayList<>();
        for (Account account : getAccounts(project, url)) {
            AuthData authData = await(getAuthData(account));
            if (authData == null || !authData.getLogin().equals(login)) {
                continue;
            }
            result.add(new AccountAuthData(account, authData, getProviderId()));
        }
        return result.size() == 1 ? result.get(0) : null;
    }

    @Override
    public final void forgetPassword(Project project, String url, AuthData authData) {
        if (!(authData instanceof AccountAuthData accountAuthData) || !accountAuthData.getAuthDataProviderId().equals(getProviderId())) {
            return;
        }

        getAuthFailureManager(project).ignoreAccount(url, accountAuthData.getAccount());
    }

    private @Nullable AccountAuthData getDefaultAccountData(Project project, String gitHostUrl) {
        Account defaultAccount = getDefaultAccount(project);
        if (defaultAccount == null) {
            return null;
        }
        HostedGitAuthenticationFailureManager authFailureManager = getAuthFailureManager(project);

        URI serverUri = getServerUri(getAccountManager(), defaultAccount);
        if (serverUri != null
            && GitHostingUrlUtil.matchHost(serverUri, gitHostUrl)
            && !authFailureManager.isAccountIgnored(gitHostUrl, defaultAccount)) {
            AuthData authData = await(getAuthData(defaultAccount));
            if (authData == null) {
                return null;
            }
            return new AccountAuthData(defaultAccount, authData, getProviderId());
        }
        return null;
    }

    private Collection<Account> getAccounts(Project project, String url) {
        return getMatchingAccounts(getAccountManager(), getAccountType(), project, getAuthFailureManager(project), url);
    }

    public static CompletableFuture<Map<Account, @Nullable AccountBasicCredentials>> getAccountsWithTokens(
        AccountManager accountManager,
        AccountType<?> accountType,
        @Nullable Project project,
        HostedGitAuthenticationFailureManager authFailureManager,
        String url
    ) {
        Map<Account, CompletableFuture<@Nullable AccountBasicCredentials>> futures = new LinkedHashMap<>();
        for (Account account : getMatchingAccounts(accountManager, accountType, project, authFailureManager, url)) {
            futures.put(account, accountManager.connect(account).<@Nullable AccountBasicCredentials>thenApply(AccountConnection::getBasicCredentials)
                .exceptionally(e -> null));
        }
        return CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Map<Account, @Nullable AccountBasicCredentials> result = new LinkedHashMap<>();
            futures.forEach((account, future) -> result.put(account, future.join()));
            return result;
        });
    }

    private static List<Account> getMatchingAccounts(AccountManager accountManager,
                                                     AccountType<?> accountType,
                                                     @Nullable Project project,
                                                     HostedGitAuthenticationFailureManager authFailureManager,
                                                     String url) {
        List<Account> result = new ArrayList<>();
        for (Account account : accountManager.getAccounts(accountType, project)) {
            URI serverUri = getServerUri(accountManager, account);
            if (serverUri != null && GitHostingUrlUtil.matchHost(serverUri, url) && !authFailureManager.isAccountIgnored(url, account)) {
                result.add(account);
            }
        }
        return result;
    }

    private static @Nullable URI getServerUri(AccountManager accountManager, Account account) {
        String serverUrl = accountManager.getServerUrl(account);
        if (serverUrl == null) {
            return null;
        }
        try {
            return URI.create(serverUrl);
        }
        catch (IllegalArgumentException e) {
            LOG.warn("Invalid server URL of account " + account.getName() + ": " + serverUrl);
            return null;
        }
    }

    private static @Nullable AuthData toAuthData(Account account, AccountConnection connection) {
        AccountBasicCredentials credentials = connection.getBasicCredentials();
        if (credentials == null) {
            return null;
        }
        String login = credentials.userName();
        if (login == null) {
            login = connection.getUserName();
        }
        if (login == null) {
            login = account.getLogin();
        }
        if (login == null) {
            return null;
        }
        return new AuthData(login, credentials.password());
    }

    private static @Nullable AuthData await(CompletableFuture<@Nullable AuthData> future) {
        try {
            return future.get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (ExecutionException e) {
            LOG.warn("Failed to get credentials", e.getCause());
            return null;
        }
    }
}
