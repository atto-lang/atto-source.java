package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Stackless control transfer for an ordinary source throw.
 *
 * This is not the guest Error object: catch and trap must expose the original
 * {@link GuestErrorValue} by reference, never this JVM Throwable. A trusted
 * compiler lowering creates a fresh signal for each dynamic source throw.
 * raise/panic/cancellation/host faults must not instantiate this type.
 */
public final class GuestOrdinaryThrow extends RuntimeException {
    private final GuestErrorValue error;

    private GuestOrdinaryThrow(GuestErrorValue error) {
        // Suppression permits bounded guest source-boundary trace markers.
        // Host stack traces/cause and the guest message stay out of the signal.
        super("ordinary guest throw", null, true, false);
        this.error = Objects.requireNonNull(error, "error");
    }

    public static GuestOrdinaryThrow signal(GuestErrorValue error) {
        return new GuestOrdinaryThrow(error);
    }

    public GuestErrorValue error() {
        return error;
    }
}
