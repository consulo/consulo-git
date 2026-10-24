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
package git4idea.commands;

import consulo.application.Application;
import consulo.component.ProcessCanceledException;
import consulo.credentialStorage.AuthData;
import consulo.credentialStorage.AuthenticationData;
import consulo.credentialStorage.CredentialAttributes;
import consulo.credentialStorage.CredentialAttributesUtil;
import consulo.credentialStorage.Credentials;
import consulo.credentialStorage.PasswordSafe;
import consulo.credentialStorage.ui.AuthDialog;
import consulo.externalProcessAuthHelper.AuthenticationGate;
import consulo.externalProcessAuthHelper.AuthenticationMode;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.io.URLUtil;
import consulo.util.io.UriUtil;
import consulo.util.lang.Couple;
import consulo.util.lang.StringUtil;
import consulo.util.lang.ref.SimpleReference;
import consulo.versionControlSystem.distributed.DvcsRememberedInputs;
import git4idea.remote.GitHttpAuthDataProvider;
import git4idea.remote.GitRememberedInputs;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * <p>Handles "ask username" and "ask password" requests from Git:
 * shows authentication dialog in the GUI, waits for user input and returns the credentials supplied by the user.</p>
 * <p>If user cancels the dialog, empty string is returned.</p>
 * <p>If no username is specified in the URL, Git queries for the username and for the password consecutively.
 * In this case to avoid showing dialogs twice, the component asks for both credentials at once,
 * and remembers the password to provide it to the Git process during the next request without requiring user interaction.</p>
 * <p>New instance of the GitAskPassGuiHandler should be created for each session, i. e. for each remote operation call.</p>
 */
public final class GitHttpGuiAuthenticator implements GitHttpAuthenticator {
    private static final Logger LOG = Logger.getInstance(GitHttpGuiAuthenticator.class);
    private static final String HTTP_SCHEME_URL_PREFIX = "http" + URLUtil.SCHEME_SEPARATOR;

    private final Project myProject;
    private final @Nullable String myPresetUrl; //taken from GitHandler, used if git does not provide url
    private final Path myWorkingDirectory;
    private final AuthenticationGate myAuthenticationGate;
    private final AuthenticationMode myAuthenticationMode;

    private boolean myWasRequested = false;
    private volatile @Nullable ProviderAndData myProviderAndData = null;

    public GitHttpGuiAuthenticator(Project project,
                                   Collection<String> urls,
                                   Path workingDirectory,
                                   AuthenticationGate authenticationGate,
                                   AuthenticationMode authenticationMode) {
        myProject = project;
        myPresetUrl = findFirstHttpUrl(urls);
        myWorkingDirectory = workingDirectory;
        myAuthenticationGate = authenticationGate;
        myAuthenticationMode = authenticationMode;
    }

    private static @Nullable String findFirstHttpUrl(Collection<String> urls) {
        for (String url : urls) {
            if (UriUtil.splitScheme(url).getFirst().startsWith("http")) {
                return url;
            }
        }
        return null;
    }

    /**
     * At this point either {@link #myProviderAndData} is filled in {@link #askUsername(String)} or username is contained in url
     */
    @Override
    public @Nullable String askPassword(String url) {
        myWasRequested = true;

        ProviderAndData providerAndData = myProviderAndData;
        if (providerAndData != null && providerAndData.getProvider() instanceof CancelledProvider) {
            LOG.debug("askPassword. Auth was cancelled.");
            return null;
        }

        if (providerAndData != null) {
            LOG.debug("askPassword. Data already filled in askUsername.");
            return providerAndData.getPassword();
        }

        Couple<String> usernameAndUrl = splitToUsernameAndUrl(getRequiredUrl(url));
        // should be provided in URL or in askUsername together with password
        String login = usernameAndUrl.first;
        if (login == null) {
            LOG.debug("askPassword. login unknown, cannot determine password without login");
            return "";
        }
        String urlWithoutUsername = usernameAndUrl.second;
        LOG.debug("askPassword. gitUrl=" + url + ", urlWithoutUsername=" + urlWithoutUsername);

        ProviderAndData newData = acquireData(urlWithoutUsername, provider -> provider.getDataForKnownLogin(login));
        myProviderAndData = newData;

        if (newData != null) {
            LOG.debug("askPassword. " + newData);
            return newData.getPassword();
        }
        LOG.debug("askPassword. no data provided");
        return "";
    }

    @Override
    public @Nullable String askUsername(String url) {
        myWasRequested = true;

        String urlWithoutUsername = splitToUsernameAndUrl(getRequiredUrl(url)).second;
        LOG.debug("askUsername. gitUrl=" + url + ", urlWithoutUsername=" + urlWithoutUsername);

        ProviderAndData providerAndData = acquireData(urlWithoutUsername, AuthDataProvider::getData);
        myProviderAndData = providerAndData;

        if (providerAndData != null && providerAndData.getProvider() instanceof CancelledProvider) {
            LOG.debug("askUsername. Auth was cancelled.");
            return null;
        }

        if (providerAndData != null) {
            LOG.debug("askUsername. " + providerAndData);
            return providerAndData.getLogin();
        }
        LOG.debug("askUsername. no data provided");
        return "";
    }

    private @Nullable ProviderAndData acquireData(String urlWithoutUsername, Function<AuthDataProvider, @Nullable AuthData> dataAcquirer) {
        return myAuthenticationGate.waitAndCompute(() -> {
            for (AuthDataProvider provider : getProviders(urlWithoutUsername)) {
                try {
                    AuthData data = dataAcquirer.apply(provider);
                    if (data != null && data.getPassword() != null) {
                        return new ProviderAndData(provider, data.getLogin(), data.getPassword());
                    }
                }
                catch (ProcessCanceledException pce) {
                    LOG.debug("Auth process cancelled by" + provider);
                    myAuthenticationGate.cancel();
                    return new ProviderAndData(new CancelledProvider(urlWithoutUsername), "", "");
                }
                catch (Throwable e) {
                    LOG.error("Can't get password from " + provider, e);
                }
            }
            return null;
        });
    }

    private List<AuthDataProvider> getProviders(String urlWithoutUsername) {
        List<AuthDataProvider> delegates = new ArrayList<>();
        PasswordSafeProvider passwordSafeProvider =
            new PasswordSafeProvider(urlWithoutUsername, GitRememberedInputs.getInstance(), PasswordSafe.getInstance());

        DialogProvider dialogProvider = new DialogProvider(urlWithoutUsername, myProject, passwordSafeProvider);

        if (myAuthenticationMode != AuthenticationMode.NONE) {
            delegates.add(passwordSafeProvider);
        }
        List<ExtensionAdapterProvider> extensionAdapterProviders = new ArrayList<>();
        Application.get().getExtensionPoint(GitHttpAuthDataProvider.class).forEach(provider ->
            extensionAdapterProviders.add(new ExtensionAdapterProvider(urlWithoutUsername, myProject, provider)));
        if (myAuthenticationMode == AuthenticationMode.SILENT) {
            for (ExtensionAdapterProvider provider : extensionAdapterProviders) {
                if (provider.myDelegate.isSilent()) {
                    delegates.add(provider);
                }
            }
        }
        else if (myAuthenticationMode == AuthenticationMode.FULL) {
            delegates.addAll(extensionAdapterProviders);
            delegates.add(dialogProvider);
        }
        return delegates;
    }

    @Override
    public void saveAuthData() {
        ProviderAndData providerAndData = myProviderAndData;
        if (providerAndData == null) {
            return;
        }

        LOG.debug("saveAuthData. " + providerAndData);
        providerAndData.getProvider().onAuthSuccess();
    }

    @Override
    public void forgetPassword() {
        ProviderAndData providerAndData = myProviderAndData;
        if (providerAndData == null) {
            return;
        }

        LOG.debug("forgetPassword. " + providerAndData);
        providerAndData.getProvider().onAuthFailure();
    }

    @Override
    public boolean wasCancelled() {
        ProviderAndData providerAndData = myProviderAndData;
        return providerAndData != null && providerAndData.getProvider() instanceof CancelledProvider;
    }

    @Override
    public boolean wasRequested() {
        return myWasRequested;
    }

    /**
     * Get the URL to be used as the authentication data identifier in the password safe and the settings.
     * git <=1.7.7 does not provide url so we have to pass it via handler
     */
    private String getRequiredUrl(@Nullable String urlFromGit) {
        if (urlFromGit != null && !StringUtil.isEmptyOrSpaces(urlFromGit)) {
            return urlFromGit;
        }
        if (myPresetUrl == null) {
            throw new IllegalStateException("Invalid remote urls in handler");
        }
        return myPresetUrl;
    }

    @Override
    public LocalizeValue getPresentableErrorMessage(List<String> errorOutput) {
        ProviderAndData providerAndData = myProviderAndData;
        if (providerAndData == null) {
            return LocalizeValue.empty();
        }
        return providerAndData.getProvider().getPresentableErrorMessage(errorOutput);
    }

    /**
     * @return non-nullable http-schemed url
     */
    private static String unifyUrl(String url) {
        if (StringUtil.isEmptyOrSpaces(url)) {
            return url;
        }

        Couple<String> schemeAndUrl = UriUtil.splitScheme(url);
        String urlWithoutScheme = schemeAndUrl.second;
        if (StringUtil.isEmptyOrSpaces(urlWithoutScheme)) {
            return url;
        }

        return HTTP_SCHEME_URL_PREFIX + urlWithoutScheme;
    }

    /**
     * Split url to a pair of username and url without username
     *
     * @return nullable username and non-nullable url
     */
    private static Couple<String> splitToUsernameAndUrl(String url) {
        if (StringUtil.isEmptyOrSpaces(url)) {
            return Couple.of(null, url);
        }

        Couple<String> schemeAndUrl = UriUtil.splitScheme(url);
        String scheme = schemeAndUrl.first;
        String urlWithoutScheme = schemeAndUrl.second;
        if (StringUtil.isEmptyOrSpaces(urlWithoutScheme)) {
            return Couple.of(null, url);
        }

        int i = urlWithoutScheme.indexOf('@');
        if (i <= 0) {
            return Couple.of(null, scheme + URLUtil.SCHEME_SEPARATOR + urlWithoutScheme);
        }
        return Couple.of(urlWithoutScheme.substring(0, i), scheme + URLUtil.SCHEME_SEPARATOR + urlWithoutScheme.substring(i + 1));
    }

    public abstract static class AuthDataProvider {
        protected final String myUrl;

        protected AuthDataProvider(String url) {
            myUrl = url;
        }

        abstract String getName();

        abstract @Nullable AuthData getData();

        abstract @Nullable AuthData getDataForKnownLogin(String login);

        abstract void onAuthSuccess();

        abstract void onAuthFailure();

        abstract LocalizeValue getPresentableErrorMessage(List<String> errorOutput);
    }

    private static final class ExtensionAdapterProvider extends AuthDataProvider {
        private final Project myProject;
        private final GitHttpAuthDataProvider myDelegate;

        private @Nullable AuthData myData = null;

        ExtensionAdapterProvider(String url, Project project, GitHttpAuthDataProvider provider) {
            super(url);
            myProject = project;
            myDelegate = provider;
        }

        @Override
        public String getName() {
            return myDelegate.getClass().getName();
        }

        @Override
        public @Nullable AuthData getData() {
            return (myData = myDelegate.getAuthData(myProject, myUrl));
        }

        @Override
        public @Nullable AuthData getDataForKnownLogin(String login) {
            return (myData = myDelegate.getAuthData(myProject, myUrl, login));
        }

        @Override
        public void onAuthSuccess() {
        }

        @Override
        public void onAuthFailure() {
            AuthData data = myData;
            if (data != null) {
                myDelegate.forgetPassword(myProject, myUrl, data);
            }
        }

        @Override
        LocalizeValue getPresentableErrorMessage(List<String> errorOutput) {
            return myDelegate.getPresentableErrorMessage(errorOutput);
        }

        @Override
        public String toString() {
            return "ExtensionAdapterProvider(" + myDelegate + ")";
        }
    }

    private static final class DialogProvider extends AuthDataProvider {
        private final Project myProject;
        private final PasswordSafeProvider myPasswordSafeDelegate;
        private boolean myCancelled;

        DialogProvider(String url, Project project, PasswordSafeProvider passwordSafeDelegate) {
            super(url);
            myProject = project;
            myPasswordSafeDelegate = passwordSafeDelegate;
        }

        @Override
        public String getName() {
            return "Dialog";
        }

        @Override
        public AuthData getData() {
            return getDataFromDialog(myUrl, myPasswordSafeDelegate.getRememberedLogin(myUrl));
        }

        @Override
        public AuthData getDataForKnownLogin(String login) {
            return getDataFromDialog(myUrl, login);
        }

        @Override
        public void onAuthSuccess() {
            myPasswordSafeDelegate.onAuthSuccess();
        }

        @Override
        public void onAuthFailure() {
            myPasswordSafeDelegate.onAuthFailure();
        }

        @Override
        LocalizeValue getPresentableErrorMessage(List<String> errorOutput) {
            return LocalizeValue.empty();
        }

        private AuthData getDataFromDialog(String url, @Nullable String username) {
            AuthenticationData data = showAuthDialog(UriUtil.splitScheme(url).second, username);
            LOG.debug("Showed dialog:" + (data != null ? "OK" : "Cancel"));
            if (data == null) {
                myCancelled = true;
                throw new ProcessCanceledException();
            }
            myPasswordSafeDelegate.setRememberPassword(data.isRememberPassword());

            AuthData authData = new AuthData(data.getLogin(), new String(data.getPassword()));
            myPasswordSafeDelegate.setData(authData);
            myPasswordSafeDelegate.savePassword(url);
            return authData;
        }

        private @Nullable AuthenticationData showAuthDialog(String url, @Nullable String username) {
            SimpleReference<AuthenticationData> dialog = SimpleReference.create();
            myProject.getUIAccess().giveAndWait(() -> {
                AuthDialog authDialog = myProject.getInstance(AuthDialog.class);
                dialog.set(authDialog.show(GitLocalize.loginDialogLabelLoginToUrl(url).get(),
                    null,
                    username,
                    null,
                    myPasswordSafeDelegate.isRememberPasswordByDefault()));
            });
            return dialog.get();
        }

        public boolean isCancelled() {
            return myCancelled;
        }
    }

    public static class PasswordSafeProvider extends AuthDataProvider {
        private final DvcsRememberedInputs myRememberedInputs;
        private final PasswordSafe myPasswordSafe;

        private @Nullable AuthData myData;

        private boolean mySavePassword = false;

        PasswordSafeProvider(String url, DvcsRememberedInputs gitRememberedInputs, PasswordSafe passwordSafe) {
            super(url);
            myRememberedInputs = gitRememberedInputs;
            myPasswordSafe = passwordSafe;
        }

        @Override
        public String getName() {
            return "Password Safe";
        }

        @Override
        public @Nullable AuthData getData() {
            String rememberedLogin = getRememberedLogin(myUrl);
            if (rememberedLogin == null) {
                return null;
            }
            return getDataForKnownLogin(rememberedLogin);
        }

        public @Nullable String getRememberedLogin(String url) {
            return myRememberedInputs.getUserNameForUrl(unifyUrl(url));
        }

        @Override
        public @Nullable AuthData getDataForKnownLogin(String login) {
            String key = makeKey(myUrl, login);
            Credentials credentials = PasswordSafe.getInstance().get(credentialAttributes(key));
            String password = StringUtil.nullize(credentials == null ? null : credentials.getPasswordAsString());
            return (myData = new AuthData(login, password));
        }

        @Override
        public void onAuthSuccess() {
        }

        @Override
        public void onAuthFailure() {
            AuthData data = myData;
            if (data == null) {
                return;
            }
            String key = makeKey(myUrl, data.getLogin());
            CredentialAttributes attributes = credentialAttributes(key);
            LOG.debug("forgetPassword. key=" + attributes.getUserName());
            myPasswordSafe.set(attributes, null);
        }

        @Override
        LocalizeValue getPresentableErrorMessage(List<String> errorOutput) {
            return LocalizeValue.empty();
        }

        public void setData(AuthData data) {
            myData = data;
        }

        public void setRememberPassword(boolean remember) {
            mySavePassword = remember;
            myPasswordSafe.setRememberPasswordByDefault(remember);
        }

        public boolean isRememberPasswordByDefault() {
            return myPasswordSafe.isRememberPasswordByDefault();
        }

        public void savePassword(String url) {
            String unifiedUrl = unifyUrl(url);

            AuthData data = myData;
            if (data == null || data.getPassword() == null) {
                return;
            }
            myRememberedInputs.addUrl(unifiedUrl, data.getLogin());

            if (!mySavePassword) {
                return;
            }

            String key = makeKey(unifiedUrl, data.getLogin());
            Credentials credentials = new Credentials(key, data.getPassword());
            myPasswordSafe.set(credentialAttributes(key), credentials);
        }

        public static CredentialAttributes credentialAttributes(String key) {
            return new CredentialAttributes(
                CredentialAttributesUtil.generateServiceName(GitLocalize.labelCredentialStoreKeyHttpPassword().get(), key), key);
        }

        /**
         * Makes the password database key for the URL: inserts the login after the scheme: http://login@url.
         */
        public static String makeKey(String url, @Nullable String login) {
            if (login == null) {
                return url;
            }
            String unifiedUrl = unifyUrl(url);
            Couple<String> pair = UriUtil.splitScheme(unifiedUrl);
            String scheme = pair.getFirst();
            if (!StringUtil.isEmpty(scheme)) {
                return scheme + URLUtil.SCHEME_SEPARATOR + login + "@" + pair.getSecond();
            }
            return login + "@" + url;
        }
    }

    private static final class CancelledProvider extends AuthDataProvider {
        CancelledProvider(String url) {
            super(url);
        }

        @Override
        String getName() {
            return "Cancelled";
        }

        @Override
        AuthData getData() {
            return new AuthData("", "");
        }

        @Override
        AuthData getDataForKnownLogin(String login) {
            return new AuthData("", "");
        }

        @Override
        void onAuthSuccess() {
        }

        @Override
        void onAuthFailure() {
        }

        @Override
        LocalizeValue getPresentableErrorMessage(List<String> errorOutput) {
            return LocalizeValue.empty();
        }
    }

    private static final class ProviderAndData {
        private final AuthDataProvider myProvider;
        private final String myLogin;
        private final String myPassword;

        private ProviderAndData(AuthDataProvider provider, String login, String password) {
            myProvider = provider;
            myLogin = login;
            myPassword = password;
        }

        private AuthDataProvider getProvider() {
            return myProvider;
        }

        private String getLogin() {
            return myLogin;
        }

        private String getPassword() {
            return myPassword;
        }

        @Override
        public String toString() {
            return "provider='" + myProvider.getName() + "', login='" + myLogin + '\'';
        }
    }
}
