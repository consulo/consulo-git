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
package git4idea.repo;

import consulo.disposer.Disposable;
import consulo.util.concurrent.coroutine.MutableObservableValue;
import consulo.util.concurrent.coroutine.ObservableValue;

import java.util.function.Consumer;

public final class GitRepositoryFlows {
    private GitRepositoryFlows() {
    }

    /**
     * Runs {@code listener} on every change of {@code repository} until {@code parentDisposable} is disposed.
     */
    public static void changesSignalFlow(GitRepository repository, Disposable parentDisposable, Runnable listener) {
        repository.getProject().getMessageBus().connect(parentDisposable).subscribe(GitRepositoryChangeListener.class, changed -> {
            if (changed == repository) {
                listener.run();
            }
        });
    }

    /**
     * Shares {@link #infoFlow} until {@code parentDisposable} is disposed and starts with the current {@link GitRepository#getInfo()}.
     */
    public static ObservableValue<GitRepoInfo> infoStateIn(GitRepository repository, Disposable parentDisposable) {
        MutableObservableValue<GitRepoInfo> state = MutableObservableValue.of(repository.getInfo());
        changesSignalFlow(repository, parentDisposable, () -> state.set(repository.getInfo()));
        return state;
    }

    /**
     * Hands the current {@link GitRepository#getInfo()} and then the new info on every change of {@code repository} to {@code consumer}
     * until {@code parentDisposable} is disposed.
     */
    public static void infoFlow(GitRepository repository, Disposable parentDisposable, Consumer<GitRepoInfo> consumer) {
        changesSignalFlow(repository, parentDisposable, () -> consumer.accept(repository.getInfo()));
        consumer.accept(repository.getInfo());
    }
}
