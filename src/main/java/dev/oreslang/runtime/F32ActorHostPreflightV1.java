package dev.oreslang.runtime;

/**
 * Staged, HOST-INTERNAL actor f32 call gate. Uses ExecutionContext's runtime-
 * owned identity, the physical carrier thread and a turn-local retirement
 * fence. It is intentionally package-private and cannot authorize guest FFI.
 *
 * The existing ActorRuntime does not yet mint this preflight from its actual
 * physical dispatch and no compiler intrinsic or native-image binding exists.
 * A successful outcome is HOST_PREFLIGHT_ONLY, never a permission to expose
 * a C pointer or to claim an AttoLang/GraalVM guest executed.
 */
final class F32ActorHostPreflightV1 {
    enum Result {
        INVALID, WRONG_ACTOR, NOT_RUNNING, BAD_SHAPE, QUOTA, HOST_PREFLIGHT_ONLY
    }

    private final ExecutionContext ownerContext;
    private final Thread physicalOwner;
    private boolean running = true;

    F32ActorHostPreflightV1(ExecutionContext trustedActorContext) {
        if (trustedActorContext == null ||
                trustedActorContext.allocationDomain().kind()
                        != ExecutionContext.AllocationKind.ACTOR ||
                trustedActorContext.allocationDomain().owner() <= 0 ||
                trustedActorContext.allocationDomain().generation() <= 0) {
            throw new IllegalArgumentException("f32 host admission requires bound actor ownership");
        }
        ownerContext = trustedActorContext;
        physicalOwner = Thread.currentThread();
    }

    synchronized Result inspect(ExecutionContext presentedContext,
                                int lhsRows, int lhsCols,
                                int rhsRows, int rhsCols,
                                long maxOutputBytes) {
        if (presentedContext == null) return Result.INVALID;
        // Object identity prevents a newly constructed value with matching
        // user-authored numbers from presenting as this particular host turn.
        // This does NOT replace ActorRuntime's missing turn-minting hook.
        if (presentedContext != ownerContext) return Result.WRONG_ACTOR;
        if (!running || Thread.currentThread() != physicalOwner)
            return Result.NOT_RUNNING;
        if (lhsRows < 1 || lhsCols < 1 || rhsRows < 1 || rhsCols < 1 ||
                lhsRows > 32 || lhsCols > 32 ||
                rhsRows > 32 || rhsCols > 32 ||
                lhsCols != rhsRows || maxOutputBytes > 4096)
            return Result.BAD_SHAPE;
        long bytes = (long) lhsRows * rhsCols * 4;
        if (maxOutputBytes < bytes) return Result.QUOTA;
        return Result.HOST_PREFLIGHT_ONLY;
    }

    synchronized void retireTurn() {
        running = false;
    }
}
