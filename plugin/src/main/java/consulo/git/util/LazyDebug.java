package consulo.git.util;

import jakarta.annotation.Nonnull;

import java.util.function.Supplier;

/**
 * @author UNV
 * @since 2025-11-21
 */
public final record LazyDebug(@Nonnull Supplier<String> stringSupplier) {
    @Override
    public String toString() {
        return stringSupplier.get();
    }
}
