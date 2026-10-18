/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
package git4idea.checkin;

import consulo.annotation.component.ExtensionImpl;
import consulo.application.dumb.DumbAware;
import consulo.application.progress.ProgressIndicator;
import consulo.git.localize.GitLocalize;
import consulo.application.Application;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.platform.base.localize.CommonLocalize;
import consulo.project.Project;
import consulo.ui.MessageBoxBuilder;
import consulo.ui.MessageBoxes;
import consulo.ui.MessageButtonRole;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.lang.Couple;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangesUtil;
import consulo.versionControlSystem.change.CommitExecutor;
import consulo.versionControlSystem.checkin.CheckinHandler;
import consulo.versionControlSystem.checkin.CheckinProjectPanel;
import consulo.versionControlSystem.checkin.CommitCheck;
import consulo.versionControlSystem.checkin.CommitInfo;
import consulo.versionControlSystem.checkin.CommitProblem;
import consulo.versionControlSystem.checkin.VcsCheckinHandlerFactory;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.Git;
import git4idea.config.GitConfigUtil;
import git4idea.config.GitVcsSettings;
import git4idea.config.GitVersion;
import git4idea.config.GitVersionSpecialty;
import git4idea.crlf.GitCrlfDialog;
import git4idea.crlf.GitCrlfProblemsDetector;
import git4idea.crlf.GitCrlfUtil;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Prohibits committing with an empty message, warns if committing into detached HEAD, checks if user name and correct CRLF attributes
 * are set.
 *
 * @author Kirill Likhodedov
 */
@ExtensionImpl
public class GitCheckinHandlerFactory extends VcsCheckinHandlerFactory {
    private static final Logger LOG = Logger.getInstance(GitCheckinHandlerFactory.class);

    public GitCheckinHandlerFactory() {
        super(GitVcs.getKey());
    }

    @Nonnull
    @Override
    protected CheckinHandler createVcsHandler(CheckinProjectPanel panel) {
        return new MyCheckinHandler(panel);
    }

    private class MyCheckinHandler extends CheckinHandler implements CommitCheck, DumbAware {
        @Nonnull
        private final Project myProject;
        @Nonnull
        private List<Change> myChanges = List.of();

        public MyCheckinHandler(@Nonnull CheckinProjectPanel panel) {
            myProject = panel.getProject();
        }

        @Override
        public ExecutionOrder getExecutionOrder() {
            return ExecutionOrder.EARLY;
        }

        @Override
        @RequiredUIAccess
        public boolean isEnabled() {
            return true;
        }

        @Nullable
        @Override
        public CommitProblem runCheck(CommitInfo commitInfo, ProgressIndicator indicator) {
            if (commitInfo.getCommitMessage().trim().isEmpty()) {
                return new GitCommitProblem(GitLocalize.gitCommitMessageEmpty(), ReturnResult.CANCEL);
            }
            if (!commitOrCommitAndPush(commitInfo.getExecutor())) {
                return null;
            }

            myChanges = commitInfo.getCommittedChanges();
            ReturnResult result = checkUserName();
            if (result == ReturnResult.COMMIT) {
                result = warnAboutCrlfIfNeeded(commitInfo.getCommittedVirtualFiles());
            }
            if (result == ReturnResult.COMMIT) {
                result = warnAboutDetachedHeadIfNeeded();
            }
            return result == ReturnResult.COMMIT
                ? null
                : new GitCommitProblem(LocalizeValue.localizeTODO("Commit was cancelled by a Git pre-commit check"), result);
        }

        @Nonnull
        private ReturnResult warnAboutCrlfIfNeeded(@Nonnull Collection<VirtualFile> files) {
            GitVcsSettings settings = GitVcsSettings.getInstance(myProject);
            if (!settings.warnAboutCrlf()) {
                return ReturnResult.COMMIT;
            }

            Git git = Application.get().getInstance(Git.class);
            GitCrlfProblemsDetector crlfHelper = GitCrlfProblemsDetector.detect(myProject, git, files);
            if (!crlfHelper.shouldWarn()) {
                return ReturnResult.COMMIT;
            }

            AtomicReference<GitCrlfDialog> dialogRef = new AtomicReference<>();
            Application application = myProject.getApplication();
            application.invokeAndWait(() -> {
                GitCrlfDialog dialog = new GitCrlfDialog(myProject);
                dialog.show();
                dialogRef.set(dialog);
            }, application.getAnyModalityState());
            GitCrlfDialog dialog = dialogRef.get();
            int decision = dialog.getExitCode();
            if (decision == GitCrlfDialog.CANCEL) {
                return ReturnResult.CANCEL;
            }
            if (decision == GitCrlfDialog.SET) {
                Collection<VirtualFile> roots = getSelectedRoots();
                if (!roots.isEmpty()) {
                    setCoreAutoCrlfAttribute(roots.iterator().next());
                }
            }
            else if (dialog.dontWarnAgain()) {
                settings.setWarnAboutCrlf(false);
            }
            return ReturnResult.COMMIT;
        }

        private void setCoreAutoCrlfAttribute(@Nonnull VirtualFile aRoot) {
            try {
                GitConfigUtil.setValue(myProject, aRoot, GitConfigUtil.CORE_AUTOCRLF, GitCrlfUtil.RECOMMENDED_VALUE, "--global");
            }
            catch (VcsException e) {
                // it is not critical: the user just will get the dialog again next time
                LOG.warn("Couldn't globally set core.autocrlf in " + aRoot, e);
            }
        }

        @Nonnull
        private ReturnResult checkUserName() {
            Project project = myProject;
            GitVcs vcs = GitVcs.getInstance(project);
            assert vcs != null;

            Collection<VirtualFile> notDefined = new ArrayList<>();
            Map<VirtualFile, Couple<String>> defined = new HashMap<>();
            Collection<VirtualFile> allRoots = new ArrayList<>(
                Arrays.asList(ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(vcs))
            );

            Collection<VirtualFile> affectedRoots = getSelectedRoots();
            for (VirtualFile root : affectedRoots) {
                try {
                    Couple<String> nameAndEmail = getUserNameAndEmailFromGitConfig(project, root);
                    String name = nameAndEmail.getFirst();
                    String email = nameAndEmail.getSecond();
                    if (name == null || email == null) {
                        notDefined.add(root);
                    }
                    else {
                        defined.put(root, nameAndEmail);
                    }
                }
                catch (VcsException e) {
                    LOG.error("Couldn't get user.name and user.email for root " + root, e);
                    // doing nothing - let commit with possibly empty user.name/email
                }
            }

            if (notDefined.isEmpty()) {
                return ReturnResult.COMMIT;
            }

            GitVersion version = vcs.getVersion();
            if (System.getenv("HOME") == null && GitVersionSpecialty.DOESNT_DEFINE_HOME_ENV_VAR.existsIn(version)) {
                DvcsUtil.showAndWait(project, () -> MessageBoxes.okError(LocalizeValue.localizeTODO(
                        "You are using Git " + version + " which doesn't define %HOME% environment variable properly.\n" +
                            "Consider updating Git to a newer version " +
                            "or define %HOME% to point to the place where the global .gitconfig is stored \n" +
                            "(it is usually %USERPROFILE% or %HOMEDRIVE%%HOMEPATH%)."
                    ))
                    .title(LocalizeValue.localizeTODO("HOME Variable Is Not Defined"))
                    .showAsync(project));
                return ReturnResult.CANCEL;
            }

            if (defined.isEmpty() && allRoots.size() > affectedRoots.size()) {
                allRoots.removeAll(affectedRoots);
                for (VirtualFile root : allRoots) {
                    try {
                        Couple<String> nameAndEmail = getUserNameAndEmailFromGitConfig(project, root);
                        String name = nameAndEmail.getFirst();
                        String email = nameAndEmail.getSecond();
                        if (name != null && email != null) {
                            defined.put(root, nameAndEmail);
                            break;
                        }
                    }
                    catch (VcsException e) {
                        LOG.error("Couldn't get user.name and user.email for root " + root, e);
                        // doing nothing - not critical not to find the values for other roots not affected by commit
                    }
                }
            }

            AtomicReference<GitUserNameNotDefinedDialog> dialogRef = new AtomicReference<>();
            Application application = project.getApplication();
            application.invokeAndWait(() -> {
                GitUserNameNotDefinedDialog userNameDialog = new GitUserNameNotDefinedDialog(project, notDefined, affectedRoots, defined);
                userNameDialog.show();
                dialogRef.set(userNameDialog);
            }, application.getAnyModalityState());
            GitUserNameNotDefinedDialog dialog = dialogRef.get();
            if (dialog.isOK()) {
                try {
                    if (dialog.isGlobal()) {
                        GitConfigUtil.setValue(
                            project,
                            notDefined.iterator().next(),
                            GitConfigUtil.USER_NAME,
                            dialog.getUserName(),
                            "--global"
                        );
                        GitConfigUtil.setValue(
                            project,
                            notDefined.iterator().next(),
                            GitConfigUtil.USER_EMAIL,
                            dialog.getUserEmail(),
                            "--global"
                        );
                    }
                    else {
                        for (VirtualFile root : notDefined) {
                            GitConfigUtil.setValue(project, root, GitConfigUtil.USER_NAME, dialog.getUserName());
                            GitConfigUtil.setValue(project, root, GitConfigUtil.USER_EMAIL, dialog.getUserEmail());
                        }
                    }
                }
                catch (VcsException e) {
                    String message = "Couldn't set user.name and user.email";
                    LOG.error(message, e);
                    DvcsUtil.showAndWait(project, () -> MessageBoxes.okError(LocalizeValue.localizeTODO(message))
                        .title(CommonLocalize.titleError())
                        .showAsync(project));
                    return ReturnResult.CANCEL;
                }
                return ReturnResult.COMMIT;
            }
            return ReturnResult.CLOSE_WINDOW;
        }

        @Nonnull
        private Couple<String> getUserNameAndEmailFromGitConfig(
            @Nonnull Project project,
            @Nonnull VirtualFile root
        ) throws VcsException {
            String name = GitConfigUtil.getValue(project, root, GitConfigUtil.USER_NAME);
            String email = GitConfigUtil.getValue(project, root, GitConfigUtil.USER_EMAIL);
            return Couple.of(name, email);
        }

        @Nonnull
        private ReturnResult warnAboutDetachedHeadIfNeeded() {
            // Warning: commit on a detached HEAD
            DetachedRoot detachedRoot = getDetachedRoot();
            if (detachedRoot == null) {
                return ReturnResult.COMMIT;
            }

            String title;
            String message;
            CharSequence rootPath = StringUtil.last(detachedRoot.myRoot.getPresentableUrl(), 50, true);
            String messageCommonStart = "The Git repository <code>" + rootPath + "</code>";
            if (detachedRoot.myRebase) {
                title = "Unfinished rebase process";
                message = messageCommonStart + " <br/> has an <b>unfinished rebase</b> process. <br/>" +
                    "You probably want to <b>continue rebase</b> instead of committing. <br/>" +
                    "Committing during rebase may lead to the commit loss. <br/>" +
                    readMore("http://www.kernel.org/pub/software/scm/git/docs/git-rebase.html", "Read more about Git rebase");
            }
            else {
                title = "Commit in detached HEAD may be dangerous";
                message = messageCommonStart + " is in the <b>detached HEAD</b> state. <br/>" +
                    "You can look around, make experimental changes and commit them, but be sure to checkout a branch not to lose your work. <br/>" +
                    "Otherwise you risk losing your changes. <br/>" +
                    readMore("http://sitaramc.github.com/concepts/detached-head.html", "Read more about detached HEAD");
            }

            String finalTitle = title;
            String finalMessage = message;
            ReturnResult result = DvcsUtil.showAndWait(myProject, () -> MessageBoxBuilder.<ReturnResult>create()
                .asWarning()
                .richText()
                .title(LocalizeValue.localizeTODO(finalTitle))
                .text(LocalizeValue.localizeTODO(finalMessage))
                .button(MessageButtonRole.CANCEL, ReturnResult.CLOSE_WINDOW)
                .asDefaultButton()
                .asExitButton()
                .button(MessageButtonRole.OK, LocalizeValue.localizeTODO("Commit"), ReturnResult.COMMIT)
                .showAsync(myProject));
            return result == null ? ReturnResult.CLOSE_WINDOW : result;
        }

        private boolean commitOrCommitAndPush(@Nullable CommitExecutor executor) {
            return executor == null || executor instanceof GitCommitAndPushExecutor;
        }

        private String readMore(String link, String message) {
            return String.format("<a href='%s'>%s</a>.", link, message);
        }

        /**
         * Scans the Git roots, selected for commit, for the root which is on a detached HEAD.
         * Returns null, if all repositories are on the branch.
         * There might be several detached repositories, - in that case only one is returned.
         * This is because the situation is very rare, while it requires a lot of additional effort of making a well-formed message.
         */
        @Nullable
        private DetachedRoot getDetachedRoot() {
            GitRepositoryManager repositoryManager = GitUtil.getRepositoryManager(myProject);
            for (VirtualFile root : getSelectedRoots()) {
                GitRepository repository = repositoryManager.getRepositoryForRootQuick(root);
                if (repository == null) {
                    continue;
                }
                if (!repository.isOnBranch()) {
                    return new DetachedRoot(root, repository.isRebaseInProgress());
                }
            }
            return null;
        }

        @Nonnull
        private Collection<VirtualFile> getSelectedRoots() {
            ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(myProject);
            Collection<VirtualFile> result = new HashSet<>();
            for (FilePath path : ChangesUtil.getPaths(myChanges)) {
                VirtualFile root = vcsManager.getVcsRootFor(path);
                if (root != null) {
                    result.add(root);
                }
            }
            return result;
        }

        private class DetachedRoot {
            final VirtualFile myRoot;
            final boolean myRebase; // rebase in progress, or just detached due to a checkout of a commit.

            public DetachedRoot(@Nonnull VirtualFile root, boolean rebase) {
                myRoot = root;
                myRebase = rebase;
            }
        }
    }

    private static class GitCommitProblem implements CommitProblem {
        private final LocalizeValue myText;
        private final CheckinHandler.ReturnResult myResult;

        private GitCommitProblem(LocalizeValue text, CheckinHandler.ReturnResult result) {
            myText = text;
            myResult = result;
        }

        @Override
        public LocalizeValue getText() {
            return myText;
        }

        @Override
        @RequiredUIAccess
        public CompletableFuture<CheckinHandler.ReturnResult> showModalSolution(Project project, CommitInfo commitInfo) {
            return CompletableFuture.completedFuture(myResult);
        }
    }
}
