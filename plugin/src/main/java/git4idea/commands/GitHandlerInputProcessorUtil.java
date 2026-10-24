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
package git4idea.commands;

import consulo.util.io.FileUtil;
import consulo.util.lang.function.ThrowableConsumer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Collection;

/**
 * Various factory methods for {@link GitHandler#setInputProcessor(ThrowableConsumer)}
 */
public final class GitHandlerInputProcessorUtil {
    private static final String DEFAULT_SEPARATOR = "\n";

    private GitHandlerInputProcessorUtil() {
    }

    public static ThrowableConsumer<OutputStream, IOException> writeLines(Collection<String> lines, Charset charset) {
        return writeLines(lines, DEFAULT_SEPARATOR, charset, false);
    }

    /**
     * Creates an input processor for {@link GitHandler} which sends provided lines to the process input stream.
     *
     * @param lines                  data to send to the stream
     * @param separator              string to separate lines with
     * @param charset                charset to use
     * @param endWithSecondSeparator send an additional separator to the output to indicate the end of data.
     *                               On Windows, the output stream won't be closed when this parameter is set to {@code true}.
     * @return an input processor (a {@link ThrowableConsumer} instance) that writes provided lines to the {@link OutputStream} passed to it.
     * @see GitHandler#setInputProcessor(ThrowableConsumer)
     */
    public static ThrowableConsumer<OutputStream, IOException> writeLines(
        Collection<String> lines,
        String separator,
        Charset charset,
        boolean endWithSecondSeparator
    ) {
        return stream -> {
            try (OutputStreamWriter writer = new OutputStreamWriter(stream, charset)) {
                for (String line : lines) {
                    writer.write(line);
                    writer.write(separator);
                }
                if (endWithSecondSeparator) {
                    writer.write(separator);
                }
                writer.flush();
            }
        };
    }

    public static ThrowableConsumer<OutputStream, IOException> redirectStream(InputStream stream) {
        return outputStream -> {
            try (outputStream) {
                FileUtil.copy(stream, outputStream);
            }
        };
    }

    public static ThrowableConsumer<OutputStream, IOException> empty() {
        return OutputStream::close;
    }
}
