/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package git4idea.remote;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.component.persist.PersistentStateComponent;
import consulo.component.persist.State;
import consulo.component.persist.Storage;
import consulo.component.persist.StoragePathMacros;
import consulo.ide.ServiceManager;
import jakarta.inject.Singleton;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Kirill Likhodedov
 */
@Singleton
@State(
  name = "GitRememberedInputs",
  storages = @Storage(file = StoragePathMacros.APP_CONFIG + "/vcs.xml")
)
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
public class GitRememberedInputs implements PersistentStateComponent<GitRememberedInputs.State> {

  private State myState = new State();

  public static class State {
    public List<UrlAndUserName> visitedUrls = new ArrayList<UrlAndUserName>();
    public String cloneParentDir = "";
    public String puttyKey = "";
  }

  public static class UrlAndUserName {
    public String url;
    public String userName;
  }

  public static GitRememberedInputs getInstance() {
    return ServiceManager.getService(GitRememberedInputs.class);
  }

  @Override
  public State getState() {
    return myState;
  }

  @Override
  public void loadState(State state) {
    myState = state;
  }

  public void addUrl(@Nonnull String url) {
    addUrl(url, "");
  }

  public void addUrl(@Nonnull String url, @Nonnull String userName) {
    for (UrlAndUserName visitedUrl : myState.visitedUrls) {
      if (visitedUrl.url.equalsIgnoreCase(url)) {  // don't add multiple entries for a single url
        if (!userName.isEmpty()) {                 // rewrite username, unless no username is specified
          visitedUrl.userName = userName;
        }
        return;
      }
    }

    UrlAndUserName urlAndUserName = new UrlAndUserName();
    urlAndUserName.url = url;
    urlAndUserName.userName = userName;
    myState.visitedUrls.add(urlAndUserName);
  }

  @Nullable
  public String getUserNameForUrl(String url) {
    for (UrlAndUserName urlAndUserName : myState.visitedUrls) {
      if (urlAndUserName.url.equalsIgnoreCase(url)) {
        return urlAndUserName.userName;
      }
    }
    return null;
  }

  @Nonnull
  public List<String> getVisitedUrls() {
    List<String> urls = new ArrayList<String>(myState.visitedUrls.size());
    for (UrlAndUserName urlAndUserName : myState.visitedUrls) {
      urls.add(urlAndUserName.url);
    }
    return urls;
  }

  public void setPuttyKey(String puttyKey) {
    myState.puttyKey = puttyKey;
  }

  public String getPuttyKey() {
    return myState.puttyKey;
  }

  public String getCloneParentDir() {
    return myState.cloneParentDir;
  }

  public void setCloneParentDir(String cloneParentDir) {
    myState.cloneParentDir = cloneParentDir;
  }

}
