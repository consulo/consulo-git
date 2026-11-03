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
package git4idea.push;

import consulo.application.Application;
import consulo.disposer.Disposable;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.ui.Component;
import consulo.ui.HasValidator;
import consulo.ui.Label;
import consulo.ui.TextBox;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.dialog.Dialog;
import consulo.ui.ex.dialog.DialogDescriptor;
import consulo.ui.ex.dialog.DialogService;
import consulo.ui.layout.VerticalLayout;
import consulo.ui.util.FormBuilder;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.push.ForcePushType;
import consulo.versionControlSystem.distributed.push.PushInfo;
import consulo.versionControlSystem.log.Hash;
import git4idea.GitRemoteBranch;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Asks expected hashes of target branches for {@link GitForcePushType#FORCE_WITH_LEASE_HASH}
 */
final class GitForcePushHashDialog extends DialogDescriptor {
    // empty value means "target branch must not exist"
    private static final Pattern HASH_PATTERN = Pattern.compile("([0-9a-fA-F]{4,40})?");

    private record Target(GitRepository repository, GitRemoteBranch branch) {
    }

    private final List<Target> myTargets;
    private final Map<GitRepository, TextBox> myHashBoxes = new LinkedHashMap<>();

    private GitForcePushHashDialog(List<Target> targets) {
        super(GitLocalize.pushForceHashDialogTitle());
        myTargets = targets;
    }

    @RequiredUIAccess
    static CompletableFuture<@Nullable ForcePushType> showAsync(Collection<PushInfo> pushInfos) {
        List<Target> targets = new ArrayList<>(pushInfos.size());
        for (PushInfo info : pushInfos) {
            if (info.getRepository() instanceof GitRepository repository
                && info.getPushSpec().getTarget() instanceof GitPushTarget target) {
                targets.add(new Target(repository, target.remoteBranch()));
            }
        }

        GitForcePushHashDialog descriptor = new GitForcePushHashDialog(targets);
        Dialog dialog = Application.get().getInstance(DialogService.class).build(descriptor);
        return dialog.showAsync().handleAsync(
            (value, error) -> error != null || value == null ? null : descriptor.createForcePushType(),
            UIAccess.current()
        );
    }

    @Override
    @RequiredUIAccess
    public Component createCenterComponent(Disposable uiDisposable) {
        boolean showRepository = myTargets.size() > 1;

        FormBuilder builder = FormBuilder.create();
        for (Target target : myTargets) {
            Hash hash = target.repository().getBranches().getHash(target.branch());

            TextBox hashBox = TextBox.create(hash == null ? "" : hash.asString());
            hashBox.addValidator(value -> isValidHash(value)
                ? null
                : new HasValidator.ValidationInfo(GitLocalize.pushForceHashDialogInvalidHash(value).get()));
            hashBox.addValueListener(event -> updateOkButtonState());
            myHashBoxes.put(target.repository(), hashBox);

            String targetName = showRepository
                ? DvcsUtil.getShortRepositoryName(target.repository()) + ": " + target.branch().getName()
                : target.branch().getName();
            builder.addLabeled(LocalizeValue.of(targetName), hashBox);
        }

        VerticalLayout layout = VerticalLayout.create();
        layout.add(Label.create(GitLocalize.pushForceHashDialogDescription()));
        layout.add(builder.build());
        return layout;
    }

    @Override
    @RequiredUIAccess
    public boolean doUpdateOkButtonState() {
        for (TextBox hashBox : myHashBoxes.values()) {
            // value can be null while text is being replaced
            if (!isValidHash(hashBox.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidHash(@Nullable String value) {
        return value != null && HASH_PATTERN.matcher(value.trim()).matches();
    }

    @RequiredUIAccess
    private GitForcePushWithHash createForcePushType() {
        Map<GitRepository, String> expectedHashes = new LinkedHashMap<>();
        for (Map.Entry<GitRepository, TextBox> entry : myHashBoxes.entrySet()) {
            String hash = entry.getValue().getValue();
            expectedHashes.put(entry.getKey(), hash == null ? "" : hash.trim());
        }
        return new GitForcePushWithHash(expectedHashes);
    }
}
