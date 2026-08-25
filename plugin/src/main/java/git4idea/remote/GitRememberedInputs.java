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
import consulo.application.Application;
import consulo.component.persist.PersistentStateComponent;
import consulo.component.persist.State;
import consulo.component.persist.Storage;
import consulo.component.persist.StoragePathMacros;
import consulo.versionControlSystem.distributed.DvcsRememberedInputs;
import jakarta.inject.Singleton;

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
public class GitRememberedInputs extends DvcsRememberedInputs implements PersistentStateComponent<GitRememberedInputs.State> {
    public static class State extends DvcsRememberedInputs.State {
        public String puttyKey = "";
    }

    public GitRememberedInputs() {
        super.loadState(new State());
    }

    public static GitRememberedInputs getInstance() {
        return Application.get().getInstance(GitRememberedInputs.class);
    }

    @Override
    public State getState() {
        return (State)super.getState();
    }

    @Override
    public void loadState(State state) {
        super.loadState(state);
    }

    public void setPuttyKey(String puttyKey) {
        getState().puttyKey = puttyKey;
    }

    public String getPuttyKey() {
        return getState().puttyKey;
    }
}
