// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote.hosting.ui;

import consulo.disposer.Disposable;
import consulo.project.Project;
import consulo.review.util.Either;
import consulo.util.concurrent.coroutine.ObservableValue;
import git4idea.repo.GitRepository;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public interface ResolveConflictsLocallyViewModel<Error> {
    /**
     * Whether there are conflicts that need to be resolved before merging.
     * <p>
     * If the value is {@code null}, there is a check currently in progress or there is something
     * else preventing us from knowing whether there are conflicts.
     */
    ObservableValue<@Nullable Boolean> getHasConflicts();

    ObservableValue<Either<Error, ResolveConflictsLocallyCoordinates>> getRequestOrError();

    ObservableValue<Boolean> isBusy();

    void performResolveConflicts(Supplier<CompletableFuture<@Nullable ResolveConflictsMethod>> chooseMethod);

    static <Error> ResolveConflictsLocallyViewModel<Error> createIn(Disposable parentDisposable,
                                                                    Project project,
                                                                    GitRepository gitRepository,
                                                                    ObservableValue<@Nullable Boolean> hasConflicts,
                                                                    ObservableValue<Either<Error, ResolveConflictsLocallyCoordinates>> requestOrError) {
        return new ResolveConflictsLocallyViewModelImpl<>(parentDisposable, project, gitRepository, hasConflicts, requestOrError);
    }
}
