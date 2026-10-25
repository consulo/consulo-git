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

import consulo.project.Project;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.change.Change;
import consulo.virtualFileSystem.VirtualFile;
import consulo.versionControlSystem.log.base.VcsFileStatusInfo;
import consulo.versionControlSystem.util.VcsUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class GitLogFullRecord extends GitLogRecord {
  private final List<VcsFileStatusInfo> myStatusInfo;

  GitLogFullRecord(Map<GitLogParser.GitLogOption, String> options,
                   List<VcsFileStatusInfo> statusInfo,
                   boolean supportsRawBody) {
    super(options, supportsRawBody);
    myStatusInfo = statusInfo;
  }

  private Collection<String> getPaths() {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (VcsFileStatusInfo info : myStatusInfo) {
      result.add(info.getFirstPath());
      if (info.getSecondPath() != null) result.add(info.getSecondPath());
    }
    return result;
  }

  List<VcsFileStatusInfo> getStatusInfos() {
    return myStatusInfo;
  }

  public List<FilePath> getFilePaths(VirtualFile root) {
    List<FilePath> res = new ArrayList<>();
    String prefix = root.getPath() + "/";
    for (String strPath : getPaths()) {
      res.add(VcsUtil.getFilePath(prefix + strPath, false));
    }
    return res;
  }

  public List<Change> parseChanges(Project project, VirtualFile vcsRoot) {
    String[] hashes = getParentsHashes();
    return GitChangesParser.parse(project, vcsRoot, myStatusInfo, getHash(), getDate(), hashes.length == 0 ? null : hashes[0]);
  }

  @Override
  public String toString() {
    return String.format("GitLogRecord{myOptions=%s, myStatusInfo=%s, mySupportsRawBody=%s, myHandler=%s}",
                         myOptions, myStatusInfo, mySupportsRawBody, myHandler);
  }
}
