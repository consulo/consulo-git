/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

import consulo.process.ProcessOutputTypes;
import consulo.project.Project;
import consulo.proxy.EventDispatcher;
import consulo.util.dataholder.Key;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.util.LineHandlerHelper;
import consulo.virtualFileSystem.VirtualFile;

import jakarta.annotation.Nonnull;
import java.io.File;
import java.util.Iterator;

/**
 * The handler that is based on per-line processing of the text.
 */
public class GitLineHandler extends GitTextHandler {
  /**
   * the partial line from stdout stream
   */
  private final StringBuilder myStdoutLine = new StringBuilder();
  /**
   * the partial line from stderr stream
   */
  private final StringBuilder myStderrLine = new StringBuilder();
  /**
   * Line listeners
   */
  private final EventDispatcher<GitLineHandlerListener> myLineListeners = EventDispatcher.create(GitLineHandlerListener.class);

  /**
   * A constructor
   *
   * @param project   a project
   * @param directory a process directory
   * @param command   a command to execute
   */
  @SuppressWarnings({"WeakerAccess"})
  public GitLineHandler(@Nonnull Project project, @Nonnull File directory, @Nonnull GitCommand command) {
    super(project, directory, command);
  }

  /**
   * A constructor
   *
   * @param project a project
   * @param vcsRoot a process directory
   * @param command a command to execute
   */
  public GitLineHandler(@Nonnull final Project project, @Nonnull final VirtualFile vcsRoot, @Nonnull final GitCommand command) {
    super(project, vcsRoot, command);
  }

  /**
   * {@inheritDoc}
   */
  protected void processTerminated(final int exitCode) {
    // force newline
    if (myStdoutLine.length() != 0) {
      onTextAvailable("\n\r", ProcessOutputTypes.STDOUT);
    }
    else if (!isStderrSuppressed() && myStderrLine.length() != 0) {
      onTextAvailable("\n\r", ProcessOutputTypes.STDERR);
    }
  }


  /**
   * Add listener
   *
   * @param listener a listener to add
   */
  public void addLineListener(GitLineHandlerListener listener) {
    super.addListener(listener);
    myLineListeners.addListener(listener);
  }

  /**
   * {@inheritDoc}
   */
  protected void onTextAvailable(final String text, final Key outputType) {
    Iterator<String> lines = LineHandlerHelper.splitText(text).iterator();
    if (ProcessOutputTypes.STDOUT == outputType) {
      notifyLines(outputType, lines, myStdoutLine);
    }
    else if (ProcessOutputTypes.STDERR == outputType) {
      notifyLines(outputType, lines, myStderrLine);
    }
  }

  /**
   * Notify listeners for each complete line. Note that in the case of stderr, the last line is saved.
   *
   * @param outputType  output type
   * @param lines       line iterator
   * @param lineBuilder a line builder
   */
  private void notifyLines(final Key outputType, final Iterator<String> lines, final StringBuilder lineBuilder) {
    if (!lines.hasNext()) return;
    if (lineBuilder.length() > 0) {
      lineBuilder.append(lines.next());
      if (lines.hasNext()) {
        // line is complete
        final String line = lineBuilder.toString();
        notifyLine(line, outputType);
        lineBuilder.setLength(0);
      }
    }
    while (true) {
      String line = null;
      if (lines.hasNext()) {
        line = lines.next();
      }

      if (lines.hasNext()) {
        notifyLine(line, outputType);
      }
      else {
        if (line != null && line.length() > 0) {
          lineBuilder.append(line);
        }
        break;
      }
    }
  }

  /**
   * Notify single line
   *
   * @param line       a line to notify
   * @param outputType output type
   */
  private void notifyLine(final String line, final Key outputType) {
    String trimmed = LineHandlerHelper.trimLineSeparator(line);
    // if line ends with return, then it is a progress line, ignore it
    if (myVcs != null && !"\r".equals(line.substring(trimmed.length()))) {
      if (outputType == ProcessOutputTypes.STDOUT && !isStdoutSuppressed() && !mySilent && !StringUtil.isEmptyOrSpaces(line)) {
        myVcs.showMessages(trimmed);
        LOG.info(line.trim());
      }
      else if (outputType == ProcessOutputTypes.STDERR && !isStderrSuppressed() && !mySilent && !StringUtil.isEmptyOrSpaces(line)) {
        myVcs.showErrorMessages(trimmed);
        LOG.info(line.trim());
      }
      else {
        LOG.debug(line.trim());
      }
    }
    myLineListeners.getMulticaster().onLineAvailable(trimmed, outputType);
  }
}
