package dev.oreslang.runtime;

/**
 * Immutable source-language duration in monotonic nanoseconds.
 *
 * <p>Constructed only by compiler-checked duration literals in the initial
 * source slice. Delays are nonnegative and overflow is rejected by the parser;
 * the type cannot be silently interchanged with an ordinary integer.</p>
 */
public record OresDuration(long nanos) {
    public OresDuration {
        if (nanos < 0) {
            throw new IllegalArgumentException("negative Oreslang Duration");
        }
    }
}
