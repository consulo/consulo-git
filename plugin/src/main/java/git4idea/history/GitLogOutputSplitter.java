// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
package git4idea.history;

import consulo.process.ProcessOutputType;
import consulo.component.ProcessCanceledException;
import consulo.util.dataholder.Key;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsException;
import git4idea.GitFormatException;
import git4idea.GitUtil;
import git4idea.commands.GitLineHandler;
import git4idea.commands.GitLineHandlerListener;
import consulo.git.localize.GitLocalize;

import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * This class processes output of git log command by feeding it line-by-line to the {@link GitLogParser}.
 * It does not store output in order to save memory.
 * Parsed records are passed to the specified {@link Consumer}.
 */
public class GitLogOutputSplitter<R extends GitLogRecord> implements GitLineHandlerListener {
  private final GitLineHandler myHandler;
  private final GitLogParser<R> myParser;
  private final Consumer<? super R> myRecordConsumer;

  private final StringBuilder myErrors = new StringBuilder();
  private @Nullable VcsException myException = null;
  private volatile boolean myCanceled;

  public GitLogOutputSplitter(GitLineHandler handler,
                       GitLogParser<R> parser,
                       Consumer<? super R> recordConsumer) {
    myHandler = handler;
    myParser = parser;
    myRecordConsumer = recordConsumer;

    myHandler.addLineListener(this);
  }

  @Override
  public void onLineAvailable(String line, Key outputType) {
    if (myCanceled) {
      return;
    }
    if (ProcessOutputType.isStderr(outputType)) {
      myErrors.append(GitUtil.cleanupErrorPrefixes(line)).append("\n");
    }
    else if (ProcessOutputType.isStdout(outputType)) {
      try {
        processOutputLine(line);
      }
      catch (ProcessCanceledException pce) {
        myCanceled = true;
        myException = new VcsException(pce);
      }
      catch (VcsException e) {
        myException = e;
      }
      catch (Throwable e) {
        myException = new VcsException(e);
      }
    }
  }

  private void processOutputLine(String line) throws VcsException {
    try {
      R record = myParser.parseLine(line);
      if (record != null) {
        record.setUsedHandler(myHandler);
        myRecordConsumer.accept(record);
      }
    }
    catch (GitFormatException e) {
      myParser.clear();
      throw new VcsException(GitLocalize.logParserExceptionMessageErrorParsingLine(StringUtil.escapeStringCharacters(line)), e);
    }
  }

  @Override
  public void processTerminated(int exitCode) {
    if (myCanceled) {
      return;
    }
    if (exitCode != 0) {
      String errorMessage = myErrors.toString();
      if (errorMessage.isEmpty()) {
        errorMessage = GitLocalize.gitErrorExit(exitCode).get();
      }
      myException = new VcsException(GitLocalize.logParserExceptionMessageErrorCommandLine(errorMessage,
                                                                                           myHandler.printableCommandLine()));
    }
    else {
      try {
        R record = myParser.finish();
        if (record != null) {
          record.setUsedHandler(myHandler);
          myRecordConsumer.accept(record);
        }
      }
      catch (ProcessCanceledException pce) {
        throw pce;
      }
      catch (Throwable t) {
        myException = new VcsException(t);
      }
    }
  }

  @Override
  public void startFailed(Throwable exception) {
    myException = new VcsException(exception);
  }

  public boolean hasErrors() {
    return myException != null;
  }

  public void reportErrors() throws VcsException {
    if (myException != null) {
      if (myException.getCause() instanceof ProcessCanceledException) {
        throw (ProcessCanceledException)myException.getCause();
      }
      throw myException;
    }
  }
}
