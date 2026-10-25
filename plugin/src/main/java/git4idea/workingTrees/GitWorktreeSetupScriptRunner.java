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
package git4idea.workingTrees;

import consulo.logging.Logger;
import consulo.process.ExecutionException;
import consulo.process.cmd.GeneralCommandLine;
import consulo.process.util.CapturingProcessUtil;
import consulo.process.util.ProcessOutput;
import consulo.project.Project;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Runs the optional setup script a user picks in the New Worktree dialog, after the worktree and its copied
 * project configuration are ready, but before the new project window opens.
 */
public final class GitWorktreeSetupScriptRunner {
    private static final Logger LOG = Logger.getInstance(GitWorktreeSetupScriptRunner.class);
    private static final int SETUP_SCRIPT_TIMEOUT = (int) TimeUnit.MINUTES.toMillis(5);

    /**
     * Runs {@code scriptPath} once, with {@code worktreeDir} as both the working directory and the sole command-line
     * argument. A failure, including a timeout, only logs the output; it never removes the worktree or blocks
     * the caller from opening the new project. The caller shows a failure notification.
     * <p>
     * Returns {@code true} only when the script starts and exits with code 0 before the timeout.
     */
    public static boolean runSetupScript(Project project, Path scriptPath, Path worktreeDir) {
        GeneralCommandLine commandLine = new GeneralCommandLine(scriptPath.toString(), worktreeDir.toString())
            .withWorkDirectory(worktreeDir.toFile());
        ProcessOutput result;
        try {
            result = CapturingProcessUtil.execAndGetOutput(commandLine, SETUP_SCRIPT_TIMEOUT);
        }
        catch (ExecutionException e) {
            LOG.warn("Failed to start the setup script " + scriptPath, e);
            return false;
        }
        if (result.isTimeout()) {
            LOG.warn("The setup script " + scriptPath + " timed out.");
            return false;
        }
        LOG.info("Setup script " + scriptPath + " finished with result " + result);
        return result.getExitCode() == 0;
    }

    private GitWorktreeSetupScriptRunner() {
    }
}
