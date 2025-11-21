/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package git4idea.update;

import consulo.application.Application;
import consulo.localize.LocalizeValue;
import consulo.platform.base.localize.CommonLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.UIUtil;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.ObjectUtil;
import consulo.util.lang.ref.SimpleReference;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.log.TimedVcsCommit;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.DialogManager;
import git4idea.history.GitHistoryUtils;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.function.Consumer;

public class GitRebaseOverMergeProblem {
    private static final Logger LOG = LoggerFactory.getLogger(GitRebaseOverMergeProblem.class);
    public static final String DESCRIPTION = "You are about to rebase merge commits.\n" +
        "This can lead to duplicate commits in history, or even data loss.\n" +
        "It is recommended to merge instead of rebase in this case.";

    public enum Decision {
        MERGE_INSTEAD(LocalizeValue.localizeTODO("Merge")),
        REBASE_ANYWAY(LocalizeValue.localizeTODO("Rebase Anyway")),
        CANCEL_OPERATION(CommonLocalize.buttonCancel());

        @Nonnull
        private final LocalizeValue myButtonText;

        Decision(@Nonnull LocalizeValue buttonText) {
            myButtonText = buttonText;
        }

        @Nonnull
        private static String[] getButtonTitles() {
            return ContainerUtil.map2Array(values(), String.class, decision -> decision.myButtonText.get());
        }

        @Nonnull
        public static Decision getOption(int index) {
            return ObjectUtil.assertNotNull(ContainerUtil.find(values(), decision -> decision.ordinal() == index));
        }

        private static int getDefaultButtonIndex() {
            return MERGE_INSTEAD.ordinal();
        }

        private static int getFocusedButtonIndex() {
            return CANCEL_OPERATION.ordinal();
        }
    }

    public static boolean hasProblem(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull String baseRef,
        @Nonnull String currentRef
    ) {
        SimpleReference<Boolean> mergeFound = SimpleReference.create(Boolean.FALSE);
        Consumer<TimedVcsCommit> detectingConsumer = commit -> mergeFound.set(true);

        String range = baseRef + ".." + currentRef;
        try {
            GitHistoryUtils.readCommits(
                project,
                root,
                Arrays.asList(range, "--merges"),
                e -> {
                },
                e -> {
                },
                detectingConsumer
            );
        }
        catch (VcsException e) {
            LOG.warn("Couldn't get git log --merges {}", range, e);
        }
        return mergeFound.get();
    }

    @Nonnull
    @RequiredUIAccess
    public static Decision showDialog() {
        SimpleReference<Decision> decision = SimpleReference.create();
        Application application = Application.get();
        application.invokeAndWait(() -> decision.set(doShowDialog()), application.getDefaultModalityState());
        return decision.get();
    }

    @Nonnull
    @RequiredUIAccess
    private static Decision doShowDialog() {
        int decision = DialogManager.showMessage(
            DESCRIPTION,
            "Rebasing Merge Commits",
            Decision.getButtonTitles(),
            Decision.getDefaultButtonIndex(),
            Decision.getFocusedButtonIndex(),
            UIUtil.getWarningIcon(),
            null
        );
        return Decision.getOption(decision);
    }
}
