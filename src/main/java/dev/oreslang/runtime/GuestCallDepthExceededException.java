package dev.oreslang.runtime;

/**
 * Controlled guest execution-budget exhaustion, raised before another
 * synchronous guest invocation is entered. This is not StackOverflowError.
 *
 * <p>The current actor/task must fail the turn; guest trap/catch must not
 * recover and continue consuming the same carrier. The supervisor may choose
 * restart or permanent termination after resources have been released.</p>
 */
public final class GuestCallDepthExceededException extends RuntimeException {
    public GuestCallDepthExceededException(int maxCalls) {
        // Stackless failure: constructing a large diagnostic trace when near a
        // native stack limit would defeat the purpose of early rejection.
        super("AttoLang synchronous guest call depth exceeded (max "
                + maxCalls + ")", null, false, false);
    }
}
