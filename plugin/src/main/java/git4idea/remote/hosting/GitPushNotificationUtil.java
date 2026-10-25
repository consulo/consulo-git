// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
import consulo.accountManagement.AccountManager;
import consulo.http.client.URIUtil;
import consulo.util.lang.Pair;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.ui.branch.GitRepositoryMappingData;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.Collection;
import java.util.Set;

public final class GitPushNotificationUtil {
    private GitPushNotificationUtil() {
    }

    /**
     * Find a matching repository mapping and account for a {@code remote} in a {@code repository}
     * <p>
     * A matching repository mapping is found in {@code knownMappings} and then an account is chosen with the following priorities:
     * 1. Saved mapping and account {@code savedAccount}
     * 2. Default account {@code defaultAccount}
     * 3. A singular account from {@code accounts}
     */
    public static <M extends HostedGitRepositoryMapping & GitRepositoryMappingData> @Nullable Pair<M, Account> findRepositoryAndAccount(
        AccountManager accountManager,
        Set<M> knownMappings,
        GitRepository repository,
        GitRemote remote,
        Collection<Account> accounts,
        @Nullable Account savedAccount,
        @Nullable Account defaultAccount
    ) {
        M mappedRepository = null;
        for (M it : knownMappings) {
            if (it.getGitRepository() == repository && it.getGitRemote().equals(remote)) {
                mappedRepository = it;
                break;
            }
        }
        if (mappedRepository == null) {
            return null;
        }

        if (savedAccount != null) {
            if (matchesAccount(accountManager, mappedRepository, savedAccount)) {
                return Pair.create(mappedRepository, savedAccount);
            }
            else {
                return null;
            }
        }

        if (defaultAccount != null && matchesAccount(accountManager, mappedRepository, defaultAccount)) {
            return Pair.create(mappedRepository, defaultAccount);
        }

        Account singularMatchingAccount = null;
        for (Account account : accounts) {
            if (matchesAccount(accountManager, mappedRepository, account)) {
                if (singularMatchingAccount != null) {
                    return null;
                }
                singularMatchingAccount = account;
            }
        }
        if (singularMatchingAccount == null) {
            return null;
        }

        return Pair.create(mappedRepository, singularMatchingAccount);
    }

    public static boolean matchesAccount(AccountManager accountManager, HostedGitRepositoryMapping mapping, Account account) {
        String serverUrl = accountManager.getServerUrl(account);
        if (serverUrl == null) {
            return false;
        }
        URI serverUri;
        try {
            serverUri = URI.create(serverUrl);
        }
        catch (IllegalArgumentException e) {
            return false;
        }
        return URIUtil.equalWithoutSchema(mapping.getRepository().getServerPath().toURI(), serverUri);
    }
}
