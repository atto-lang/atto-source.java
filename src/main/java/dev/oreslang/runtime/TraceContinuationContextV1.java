package dev.oreslang.runtime;

/**
 * Immutable W3C trace-parent propagation for logical source task turns.
 *
 * A physical carrier is never an owner of a mutable Span or scoped resource.
 * CURRENT is only a per-dispatch cache: each heap-owned SourceTask captures
 * its TraceContextV1 and reinstalls it on every resume, including on a new
 * carrier after await. A sibling Task receives a snapshot, not shared state.
 *
 * This is not a security credential, a mutable span or a cross-tenant
 * propagation entitlement. The actor mailbox/supervisor must independently
 * validate tenancy and message provenance before accepting a traceparent.
 * Never store LogicalTraceScopeV1.Scope or a Continuation ticket here.
 */
public final class TraceContinuationContextV1 {
    private static final ThreadLocal<TraceContextV1> CURRENT =
            new ThreadLocal<>();

    private TraceContinuationContextV1() { }

    /**
     * Return the immutable context active on this physical dispatch turn.
     * Callers starting a child SourceTask may inherit this snapshot.
     */
    public static TraceContextV1 current() {
        return CURRENT.get();
    }

    /**
     * Set a task's immutable context only for the current physical turn.
     * Returns the previously installed value, which MUST be restored by
     * the caller in finally. Null removes the carrier's entry entirely.
     *
     * This is a host-runtime adapter, not a guest-facing tracing primitive.
     */
    public static TraceContextV1 install(TraceContextV1 context) {
        TraceContextV1 previous = CURRENT.get();
        if (context == null) CURRENT.remove();
        else CURRENT.set(context);
        return previous;
    }
}
