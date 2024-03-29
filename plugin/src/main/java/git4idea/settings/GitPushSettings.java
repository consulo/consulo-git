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
package git4idea.settings;

import consulo.component.persist.PersistentStateComponent;
import consulo.component.persist.Storage;
import consulo.component.persist.StoragePathMacros;
import consulo.ide.ServiceManager;
import consulo.project.Project;
import git4idea.config.UpdateMethod;

/**
 * @author Kirill Likhodedov
 */
@consulo.component.persist.State(name = "Git.Push.Settings", storages = {@Storage(file = StoragePathMacros.WORKSPACE_FILE)})
public class GitPushSettings implements PersistentStateComponent<GitPushSettings.State> {

  private State myState = new State();

  public static class State {
    public boolean myUpdateAllRoots = true;
    public UpdateMethod myUpdateMethod = UpdateMethod.MERGE;
  }

  public static GitPushSettings getInstance(Project project) {
    return ServiceManager.getService(project, GitPushSettings.class);
  }

  @Override
  public State getState() {
    return myState;
  }

  @Override
  public void loadState(State state) {
    myState = state;
  }

  public boolean shouldUpdateAllRoots() {
    return myState.myUpdateAllRoots;
  }
  
  public void setUpdateAllRoots(boolean updateAllRoots) {
    myState.myUpdateAllRoots = updateAllRoots;
  }

  public UpdateMethod getUpdateMethod() {
    return myState.myUpdateMethod;
  }

  public void setUpdateMethod(UpdateMethod updateMethod) {
    myState.myUpdateMethod = updateMethod;
  }

}
