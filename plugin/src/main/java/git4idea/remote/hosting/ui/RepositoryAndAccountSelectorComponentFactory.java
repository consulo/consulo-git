// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
import consulo.accountManagement.AccountManager;
import consulo.application.Application;
import consulo.dataContext.DataManager;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.review.list.error.ErrorStatusAlignment;
import consulo.review.list.error.ErrorStatusPanelFactory;
import consulo.review.list.error.ErrorStatusPresenter;
import consulo.review.localize.ReviewLocalize;
import consulo.ui.Button;
import consulo.ui.ComboBox;
import consulo.ui.Component;
import consulo.ui.ImageBox;
import consulo.ui.Label;
import consulo.ui.TextAttribute;
import consulo.ui.UIAccess;
import consulo.ui.PopupPosition;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.DefaultActionGroup;
import consulo.ui.ex.popup.JBPopupFactory;
import consulo.ui.ex.popup.ListPopup;
import consulo.ui.image.Image;
import consulo.ui.image.ImageEffects;
import consulo.ui.layout.DockLayout;
import consulo.ui.layout.HorizontalLayout;
import consulo.ui.layout.VerticalLayout;
import consulo.ui.style.ComponentColors;
import consulo.ui.model.FlatDataModel;
import consulo.ui.model.MutableFlatDataModel;
import consulo.util.concurrent.coroutine.ObservableValue;
import git4idea.remote.hosting.HostedGitRepositoryMapping;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class RepositoryAndAccountSelectorComponentFactory<M extends HostedGitRepositoryMapping> {
    private static final int AVATAR_SIZE = 20;

    private final RepositoryAndAccountSelectorViewModel<M> myVm;

    public RepositoryAndAccountSelectorComponentFactory(RepositoryAndAccountSelectorViewModel<M> vm) {
        myVm = vm;
    }

    @RequiredUIAccess
    public Component create(Disposable parentDisposable,
                            Function<M, LocalizeValue> repoNamer,
                            LocalizeValue submitActionText,
                            List<Button> loginButtons,
                            ErrorStatusPresenter<RepositoryAndAccountSelectorViewModel.Error> errorPresenter) {
        return create(parentDisposable, null, repoNamer, it -> List.of(), submitActionText, loginButtons, errorPresenter);
    }

    @RequiredUIAccess
    public Component create(Disposable parentDisposable,
                            @Nullable Supplier<@Nullable Account> defaultAccountHolder,
                            Function<M, LocalizeValue> repoNamer,
                            Function<M, List<AnAction>> accountsPopupActionsSupplier,
                            LocalizeValue submitActionText,
                            List<Button> loginButtons,
                            ErrorStatusPresenter<RepositoryAndAccountSelectorViewModel.Error> errorPresenter) {
        UIAccess uiAccess = UIAccess.current();

        MutableFlatDataModel<M> repoModel = FlatDataModel.of(List.copyOf(myVm.getRepositoriesState().get()));
        ComboBox<M> repoCombo = ComboBox.create(repoModel);
        repoCombo.setRenderer((presentation, item) -> {
            M mapping = item.getValue();
            if (mapping == null) {
                return;
            }
            presentation.append(repoNamer.apply(mapping));
            presentation.append(LocalizeValue.of("  " + mapping.getRemote().remote().getName()), TextAttribute.GRAYED);
        });
        bind(parentDisposable, uiAccess, myVm.getRepositoriesState(), repositories -> {
            M selected = myVm.getRepoSelectionState().get();
            repoModel.replaceAll(List.copyOf(repositories));
            repoCombo.setValue(selected);
        });
        bind(parentDisposable, uiAccess, myVm.getRepoSelectionState(), selected -> {
            if (!Objects.equals(repoCombo.getValue(), selected)) {
                repoCombo.setValue(selected);
            }
        });
        repoCombo.addValueListener(event -> myVm.getRepoSelectionState().set(event.getValue()));

        AccountManager accountManager = Application.get().getInstance(AccountManager.class);
        MutableFlatDataModel<Account> accountModel = FlatDataModel.of(myVm.getAccountsState().get());
        ComboBox<Account> accountCombo = ComboBox.create(accountModel);
        Set<String> avatarSubscriptions = new HashSet<>();
        Map<String, Image> avatars = new ConcurrentHashMap<>();
        accountCombo.setRenderer((presentation, item) -> {
            Account account = item.getValue();
            if (account == null) {
                presentation.append(ReviewLocalize.accountChooseLink(), TextAttribute.GRAYED);
                return;
            }
            Image avatar = avatars.get(account.getId());
            if (avatar != null) {
                presentation.withIcon(avatar);
            }
            String name = account.getName();
            presentation.append(LocalizeValue.of(name == null || name.isEmpty() ? account.getLogin() : name));
        });
        bind(parentDisposable, uiAccess, myVm.getAccountsState(), accounts -> {
            Account selected = myVm.getAccountSelectionState().get();
            accountModel.replaceAll(accounts);
            if (selected == null || !accounts.contains(selected)) {
                Account defaultAccount = defaultAccountHolder == null ? null : defaultAccountHolder.get();
                if (defaultAccount != null && accounts.contains(defaultAccount)) {
                    selected = defaultAccount;
                }
                else if (!accounts.isEmpty()) {
                    selected = accounts.get(0);
                }
                else {
                    selected = null;
                }
                myVm.getAccountSelectionState().set(selected);
            }
            accountCombo.setValue(selected);
            for (Account account : accounts) {
                if (avatarSubscriptions.add(account.getId())) {
                    ObservableValue<@Nullable Image> avatar = accountManager.getAvatar(account);
                    Image loaded = avatar.get();
                    if (loaded != null) {
                        avatars.put(account.getId(), ImageEffects.resize(loaded, AVATAR_SIZE));
                    }
                    Disposer.register(parentDisposable, avatar.addListener(image -> uiAccess.give(() -> {
                        if (image != null) {
                            avatars.put(account.getId(), ImageEffects.resize(image, AVATAR_SIZE));
                        }
                        if (accountModel.indexOf(account) >= 0) {
                            accountModel.update(account);
                        }
                    }))::run);
                }
            }
        });
        bind(parentDisposable, uiAccess, myVm.getAccountSelectionState(), selected -> {
            if (!Objects.equals(accountCombo.getValue(), selected)) {
                accountCombo.setValue(selected);
            }
        });
        accountCombo.addValueListener(event -> myVm.getAccountSelectionState().set(event.getValue()));

        Button accountActionsButton = Button.create(LocalizeValue.empty());
        accountActionsButton.setIcon(PlatformIconGroup.generalAdd());
        accountActionsButton.addClickListener(event -> {
            M repo = myVm.getRepoSelectionState().get();
            List<AnAction> actions = repo == null ? List.of() : accountsPopupActionsSupplier.apply(repo);
            if (actions.isEmpty()) {
                return;
            }
            ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup(
                null,
                new DefaultActionGroup(actions),
                DataManager.getInstance().getDataContext(accountActionsButton),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true
            );
            popup.showBy(accountActionsButton, null, PopupPosition.BOTTOM);
        });
        bind(parentDisposable, uiAccess, myVm.getRepoSelectionState(),
            repo -> accountActionsButton.setVisible(repo != null && !accountsPopupActionsSupplier.apply(repo).isEmpty()));

        bind(parentDisposable, uiAccess, myVm.getBusyState(), busy -> {
            repoCombo.setEnabled(!busy);
            accountCombo.setEnabled(!busy);
            accountActionsButton.setEnabled(!busy);
        });

        Button submitButton = Button.create(submitActionText, event -> myVm.submitSelection());
        bind(parentDisposable, uiAccess, myVm.getSubmitAvailableState(), submitButton::setVisible);
        bind(parentDisposable, uiAccess, myVm.getBusyState(), busy -> submitButton.setEnabled(!busy));

        ImageBox busyLabel = ImageBox.create(Image.busy());
        bind(parentDisposable, uiAccess, myVm.getBusyState(), busyLabel::setVisible);

        HorizontalLayout actionsPanel = HorizontalLayout.create();
        actionsPanel.add(submitButton);
        for (Button loginButton : loginButtons) {
            actionsPanel.add(loginButton);
        }
        actionsPanel.add(busyLabel);

        Component errorPanel = Application.get().getInstance(ErrorStatusPanelFactory.class)
            .create(parentDisposable, myVm.getErrorState(), errorPresenter, ErrorStatusAlignment.LEFT);

        Label note = Label.create(ReviewLocalize.reviewLoginNoteMore());
        note.setForegroundColor(ComponentColors.DISABLED_TEXT);

        DockLayout combos = DockLayout.create();
        combos.center(repoCombo);
        HorizontalLayout accountPanel = HorizontalLayout.create();
        accountPanel.add(accountCombo);
        accountPanel.add(accountActionsButton);
        combos.right(accountPanel);

        VerticalLayout root = VerticalLayout.create();
        root.add(combos);
        root.add(actionsPanel);
        root.add(errorPanel);
        root.add(note);
        return root;
    }

    private static <T> void bind(Disposable parentDisposable,
                                 UIAccess uiAccess,
                                 ObservableValue<T> value,
                                 @RequiredUIAccess Consumer<T> consumer) {
        consumer.accept(value.get());
        Disposer.register(parentDisposable, value.addListener(newValue -> uiAccess.give(() -> consumer.accept(value.get())))::run);
    }
}
