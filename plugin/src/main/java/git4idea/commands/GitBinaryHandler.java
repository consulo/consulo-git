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
package git4idea.commands;

import consulo.ide.localize.IdeLocalize;
import consulo.process.BinaryProcessHandler;
import consulo.process.ExecutionException;
import consulo.process.ProcessHandler;
import consulo.process.ProcessHandlerBuilder;
import consulo.process.ProcessOutputTypes;
import consulo.process.event.ProcessEvent;
import consulo.process.event.ProcessListener;
import consulo.util.dataholder.Key;
import consulo.project.Project;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitVcs;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The handler that allows consuming binary data as byte array
 */
public class GitBinaryHandler extends GitHandler {
    private static final int BUFFER_SIZE = 8 * 1024;

    @Nonnull
    private final ByteArrayOutputStream myStdout = new ByteArrayOutputStream();
    @Nonnull
    private final ByteArrayOutputStream myStderr = new ByteArrayOutputStream();
    @Nullable
    private volatile BinaryProcessHandler myHandler;
    @Nonnull
    private final AtomicReference<VcsException> myException = new AtomicReference<>();

    public GitBinaryHandler(Project project, VirtualFile vcsRoot, GitCommand command) {
        super(project, vcsRoot, command);
    }

    @Override
    protected ProcessHandler startProcess() throws ExecutionException {
        myCommandLine.setCharset(getCharset());
        BinaryProcessHandler handler = (BinaryProcessHandler) ProcessHandlerBuilder.create(myCommandLine).binary().build();
        myHandler = handler;
        return handler;
    }

    @Override
    protected void startHandlingStreams() {
        BinaryProcessHandler handler = myHandler;
        if (handler == null) {
            return;
        }
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(ProcessEvent event, Key outputType) {
                if (outputType == ProcessOutputTypes.STDERR) {
                    byte[] bytes = event.getText().getBytes(getCharset());
                    synchronized (myStderr) {
                        myStderr.write(bytes, 0, bytes.length);
                    }
                }
            }
        });
        handler.startNotify();
    }

    @Override
    public void destroyProcess() {
        BinaryProcessHandler handler = myHandler;
        if (handler != null) {
            handler.destroyProcess();
        }
    }

    @Override
    protected void waitForProcess() {
        BinaryProcessHandler handler = myHandler;
        if (handler == null) {
            setExitCode(255);
        }
        else {
            handler.waitFor();
            Integer exitCode = handler.getExitCode();
            setExitCode(exitCode != null ? exitCode : 255);
            byte[] output = handler.getOutput();
            myStdout.write(output, 0, output.length);
        }
        listeners().processTerminated(getExitCode());
    }

    /**
     * Run in the current thread and return the data as array
     *
     * @return the binary data
     * @throws VcsException in case of the problem with running git
     */
    public byte[] run() throws VcsException {
        addListener(new GitHandlerListener() {
            @Override
            public void processTerminated(int exitCode) {
                if (exitCode != 0 && !isIgnoredErrorCode(exitCode)) {
                    Charset cs = getCharset();
                    String message = new String(myStderr.toByteArray(), cs);
                    if (message.length() == 0) {
                        //noinspection ThrowableResultOfMethodCallIgnored
                        if (myException.get() != null) {
                            message = IdeLocalize.finishedWithExitCodeTextMessage(exitCode).get();
                        }
                        else {
                            message = null;
                        }
                    }
                    else if (!isStderrSuppressed()) {
                        GitVcs.getInstance(myProject).showErrorMessages(message);
                    }
                    if (message != null) {
                        //noinspection ThrowableInstanceNeverThrown
                        VcsException e = myException.getAndSet(new VcsException(message));
                        if (e != null) {
                            LOG.warn("Dropping previous exception: ", e);
                        }
                    }
                }
            }

            @Override
            public void startFailed(Throwable exception) {
                //noinspection ThrowableInstanceNeverThrown
                VcsException e = myException.getAndSet(new VcsException("Start failed: " + exception.getMessage(), exception));
                if (e != null) {
                    LOG.warn("Dropping previous exception: ", e);
                }
            }
        });
        GitHandlerUtil.runInCurrentThread(this, null);
        //noinspection ThrowableResultOfMethodCallIgnored
        if (myException.get() != null) {
            throw myException.get();
        }
        return myStdout.toByteArray();
    }
}
