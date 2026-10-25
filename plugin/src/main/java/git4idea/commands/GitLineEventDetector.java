// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.commands;

public interface GitLineEventDetector extends GitLineHandlerListener {
    boolean isDetected();
}
