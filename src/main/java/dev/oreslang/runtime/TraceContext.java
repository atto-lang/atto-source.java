package dev.oreslang.runtime;

/**
 * Immutable, execution-scoped trace identity for the runtime instrumentation ABI.
 *
 * <p>Only the owning logical task retains this context between dispatches.
 * CURRENT is a carrier-turn cache, not the storage authority: the scheduler
 * installs it before a resume and restores the prior carrier value afterwards.
 * Completion callbacks on arbitrary Future producer threads MUST NOT inherit
 * this context. Trace identity is diagnostic metadata, never a capability
 * granting access to actor or task state.</p>
 *
 * <p>This is the context propagation substrate, not an exporting tracer or a
 * span lifecycle manager. Compiler-lowered lexical scopes must separately end
 * each span exactly once on success, failure or cancellation after cleanup.
 * No application-facing source API is registered by this class.</p>
 */
public final class TraceContext {
    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

    private final String traceId;
    private final String spanId;
    private final boolean sampled;

    private TraceContext(String traceId, String spanId, boolean sampled) {
        this.traceId = validHexId(traceId, 32, "trace");
        this.spanId = validHexId(spanId, 16, "span");
        this.sampled = sampled;
    }

    /** Runtime-created root context. IDs use the W3C 128/64-bit lowercase-hex wire shape. */
    public static TraceContext root(String traceId, String spanId, boolean sampled) {
        return new TraceContext(traceId, spanId, sampled);
    }

    /** Child execution context; a span implementation must generate a fresh span ID. */
    public TraceContext child(String childSpanId) {
        if (spanId.equals(childSpanId)) {
            throw new IllegalArgumentException("child span ID must differ from the parent span ID");
        }
        return new TraceContext(traceId, childSpanId, sampled);
    }

    public String traceId() { return traceId; }

    public String spanId() { return spanId; }

    public boolean sampled() { return sampled; }

    /** Current trace identity, scoped to this dispatch turn only. */
    public static TraceContext current() {
        return CURRENT.get();
    }

    /**
     * Install or clear the current carrier-turn cache. Returns the previous
     * context so a scheduler or host boundary can restore it in a finally.
     * A suspended task preserves the context independently of this thread.
     */
    public static TraceContext install(TraceContext context) {
        TraceContext previous = CURRENT.get();
        if (context == null) CURRENT.remove();
        else CURRENT.set(context);
        return previous;
    }

    private static String validHexId(String id, int width, String kind) {
        if (id == null || id.length() != width) {
            throw new IllegalArgumentException(kind + " ID must be " + width + " lowercase hex digits");
        }
        boolean anyNonzero = false;
        for (int i = 0; i < id.length(); i++) {
            char value = id.charAt(i);
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                throw new IllegalArgumentException(kind + " ID must be lowercase hex");
            }
            if (value != '0') anyNonzero = true;
        }
        if (!anyNonzero) {
            throw new IllegalArgumentException(kind + " ID cannot be all zero");
        }
        return id;
    }
}
