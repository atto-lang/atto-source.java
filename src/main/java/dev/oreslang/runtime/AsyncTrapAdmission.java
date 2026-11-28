package dev.oreslang.runtime;

/** Conservative typed-only admission at a retired async callable boundary. */
public final class AsyncTrapAdmission {
    private static final int MAX_SOURCE_MARKERS = 64;

    private AsyncTrapAdmission() { }

    /**
     * A wrapper or a host failure is not a directly delivered guest throw.
     * Do not traverse an unknown Throwable or erase suppressed cleanup failures.
     * Until typed suppression is represented, only inert source markers qualify.
     */
    public static boolean accepts(Throwable failure, boolean cancellationUnwinding,
            boolean cleanupRetired) {
        if (cancellationUnwinding || !cleanupRetired
                || !(failure instanceof GuestOrdinaryThrow)) return false;
        Throwable[] suppressed = failure.getSuppressed();
        if (suppressed.length > MAX_SOURCE_MARKERS) return false;
        for (Throwable diagnostic : suppressed) {
            if (!(diagnostic instanceof SourceBoundaryTrace)) return false;
        }
        return true;
    }
}
