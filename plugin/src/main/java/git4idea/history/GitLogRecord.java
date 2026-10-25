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
package git4idea.history;

import consulo.logging.Logger;
import consulo.util.lang.StringUtil;
import consulo.util.collection.ArrayUtil;
import consulo.util.collection.ContainerUtil;
import git4idea.GitUtil;
import git4idea.commands.GitHandler;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static git4idea.history.GitLogParser.GitLogOption.AUTHOR_EMAIL;
import static git4idea.history.GitLogParser.GitLogOption.AUTHOR_NAME;
import static git4idea.history.GitLogParser.GitLogOption.AUTHOR_TIME;
import static git4idea.history.GitLogParser.GitLogOption.BODY;
import static git4idea.history.GitLogParser.GitLogOption.COMMITTER_EMAIL;
import static git4idea.history.GitLogParser.GitLogOption.COMMITTER_NAME;
import static git4idea.history.GitLogParser.GitLogOption.COMMIT_TIME;
import static git4idea.history.GitLogParser.GitLogOption.HASH;
import static git4idea.history.GitLogParser.GitLogOption.PARENTS;
import static git4idea.history.GitLogParser.GitLogOption.RAW_BODY;
import static git4idea.history.GitLogParser.GitLogOption.REF_NAMES;
import static git4idea.history.GitLogParser.GitLogOption.SHORT_REF_LOG_SELECTOR;
import static git4idea.history.GitLogParser.GitLogOption.SUBJECT;
import static git4idea.history.GitLogParser.GitLogOption.TREE;

/**
 * One record (commit information) returned by git log output.
 * The access methods try heavily to return some default value if real is unavailable, for example, blank string is better than null.
 * BUT if one tries to get an option which was not specified to the GitLogParser, one will get null.
 *
 * @see GitLogParser
 */
public class GitLogRecord {
  private static final Logger LOG = Logger.getInstance(GitLogRecord.class);

  protected final Map<GitLogParser.GitLogOption, String> myOptions;
  protected final boolean mySupportsRawBody;

  protected GitHandler myHandler;

  GitLogRecord(Map<GitLogParser.GitLogOption, String> options,
               boolean supportsRawBody) {
    myOptions = options;
    mySupportsRawBody = supportsRawBody;
  }

  private String lookup(GitLogParser.GitLogOption key) {
    String value = myOptions.get(key);
    if (value == null) {
      LOG.error("Missing value for option " + key + ", while executing " + myHandler);
      return "";
    }
    return value;
  }

  // trivial access methods
  public String getHash() {
    return lookup(HASH);
  }

  public String getTreeHash() {
    return lookup(TREE);
  }

  public String getAuthorName() {
    return lookup(AUTHOR_NAME);
  }

  public String getAuthorEmail() {
    return lookup(AUTHOR_EMAIL);
  }

  public String getCommitterName() {
    return lookup(COMMITTER_NAME);
  }

  public String getCommitterEmail() {
    return lookup(COMMITTER_EMAIL);
  }

  public String getSubject() {
    return lookup(SUBJECT);
  }

  public String getBody() {
    return lookup(BODY);
  }

  public String getRawBody() {
    return lookup(RAW_BODY);
  }

  public String getShortenedRefLog() {
    return lookup(SHORT_REF_LOG_SELECTOR);
  }

  // access methods with some formatting or conversion

  public Date getDate() {
    return new Date(getCommitTime());
  }

  long getCommitTime() {
    try {
      return GitLogUtil.parseTime(myOptions.get(COMMIT_TIME));
    }
    catch (NumberFormatException e) {
      LOG.error("Couldn't get commit time from " + this + ", while executing " + myHandler, e);
      return 0;
    }
  }

  public long getAuthorTimeStamp() {
    try {
      return GitLogUtil.parseTime(myOptions.get(AUTHOR_TIME));
    }
    catch (NumberFormatException e) {
      LOG.error("Couldn't get author time from " + this + ", while executing " + myHandler, e);
      return 0;
    }
  }

  String getFullMessage() {
    return (mySupportsRawBody ? getRawBody() : getSubject() + "\n\n" + getBody()).stripTrailing();
  }

  public String [] getParentsHashes() {
    final String parents = lookup(PARENTS);
    if (parents.trim().isEmpty()) return ArrayUtil.EMPTY_STRING_ARRAY;
    return parents.split(" ");
  }

  public Collection<String> getRefs() {
    final String decorate = myOptions.get(REF_NAMES);
    return parseRefNames(decorate);
  }

  public Map<GitLogParser.GitLogOption, String> getOptions() {
    return myOptions;
  }

  public boolean isSupportsRawBody() {
    return mySupportsRawBody;
  }

  private static List<String> parseRefNames(final @Nullable String decoration) {
    if (decoration == null) {
      return List.of();
    }
    final int startParentheses = decoration.indexOf("(");
    final int endParentheses = decoration.lastIndexOf(")");
    if ((startParentheses == -1) || (endParentheses == -1)) return Collections.emptyList();
    String refs = decoration.substring(startParentheses + 1, endParentheses);
    String[] names = refs.split(", ");
    List<String> result = new ArrayList<>();
    for (String item : names) {
      final String POINTER = " -> ";   // HEAD -> refs/heads/master in Git 2.4.3+
      if (item.contains(POINTER)) {
        List<String> parts = StringUtil.split(item, POINTER);
        result.addAll(ContainerUtil.map(parts, String::trim));
      }
      else {
        int colon = item.indexOf(':'); // tags have the "tag:" prefix.
        String raw = colon > 0 ? item.substring(colon + 1).trim() : item;
        result.add(raw);
      }
    }
    return result;
  }

  /**
   * for debugging purposes - see {@link GitUtil#parseTimestampWithNFEReport(String, GitHandler, String)}.
   */
  public void setUsedHandler(GitHandler handler) {
    myHandler = handler;
  }

  @Override
  public String toString() {
    return String.format("GitLogRecord{myOptions=%s, mySupportsRawBody=%s, myHandler=%s}",
                         myOptions, mySupportsRawBody, myHandler);
  }
}
