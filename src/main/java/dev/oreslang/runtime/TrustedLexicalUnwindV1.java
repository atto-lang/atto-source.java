package dev.oreslang.runtime;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Host-only lexical cleanup boundary for compiler-issued allocation scopes.
 * The cleanup callback is called exactly once on value return and both ordinary
 * and exceptional unwinds. This utility does not mint guest ownership rights.
 */
final class TrustedLexicalUnwindV1 {
    private static final int MAX_SUPPRESSED = 64;

    private TrustedLexicalUnwindV1() { }

    static <T> T run(Supplier<T> body, Runnable cleanup) {
        Objects.requireNonNull(body, "checked body");
        Objects.requireNonNull(cleanup, "checked cleanup");
        Throwable original = null;
        try {
            return body.get();
        } catch (RuntimeException | Error failure) {
            original = failure;
            throw failure;
        } finally {
            try {
                cleanup.run();
            } catch (RuntimeException | Error cleanupFailure) {
                if (original == null) throw cleanupFailure;
                // An emergency or authority failure must not be hidden by a
                // previously ordinary body failure. It wins the unwind.
                if (isFatal(cleanupFailure) && !isFatal(original)) {
                    suppress(cleanupFailure, original);
                    throw cleanupFailure;
                }
                suppress(original, cleanupFailure);
            }
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof Error || failure instanceof SecurityException;
    }

    private static void suppress(Throwable primary, Throwable secondary) {
        if (primary != secondary && primary.getSuppressed().length < MAX_SUPPRESSED) {
            primary.addSuppressed(secondary);
        }
    }
}
