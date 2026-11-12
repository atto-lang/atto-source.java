package dev.oreslang.runtime;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Bounded, per-isolate numeric execution observations emitted from trusted
 * compiler/runtime boundaries. No guest object, source string, credential,
 * native pointer or userland callback may be captured.
 *
 * These are NOT OpenTelemetry spans; the native Atto collector and W3C context
 * are independent future components. Recording is OFF by default.
 *
 * A trace-capable image includes dormant entry probes. The operator can
 * enable existing CALLS sites without code changes. The policy cannot add
 * sites absent from an AOT binary.
 */
public final class AutoTraceRecorder {
    static final int DEFAULT_CAPACITY = 4096;
    private static final int PHASE_CALL = 100;

    private final TraceProbeEpoch policy;
    private final AtomicInteger cursor = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicIntegerArray published;
    private final int[] siteIds;
    private final int[] outcomes;
    private final long[] durations;

    public AutoTraceRecorder(int capacity, boolean sealed) {
        if (capacity < 1 || capacity > 65536) {
            throw new IllegalArgumentException("telemetry capacity must be 1..65536");
        }
        policy = new TraceProbeEpoch(TraceProbeEpoch.Level.CALLS, sealed);
        siteIds = new int[capacity];
        outcomes = new int[capacity];
        durations = new long[capacity];
        published = new AtomicIntegerArray(capacity);
    }

    /**
     * Trusted embedder-only gate. It does not grant I/O or export permission.
     * Do not surface this method through guest interop.
     */
    boolean reconfigureCalls(boolean enabled) {
        return policy.reconfigure(
                enabled ? TraceProbeEpoch.Level.CALLS : TraceProbeEpoch.Level.OFF);
    }

    boolean enabled() {
        return policy.currentMode() != TraceProbeEpoch.Level.OFF;
    }

    public long enterCall() {
        // Both off and unsupported CALLS have no clock read or allocation.
        if (!policy.shouldRecord(TraceProbeEpoch.Level.CALLS)) return 0L;
        long started = System.nanoTime();
        return started == 0L ? 1L : started; // zero is an inert token
    }

    /**
     * Exits only calls which were admitted at entry. In-flight calls may
     * finish after a mode transition; they retain the original policy.
     * The numeric site ID must be compiler-owned, not a user string.
     */
    public void exitCall(long started, int siteId, boolean failed) {
        if (started == 0L) return;
        long delta = System.nanoTime() - started;
        if (delta < 0 || siteId == 0) return; // invalid sample is inert

        int index;
        while (true) {
            index = cursor.get();
            if (index >= siteIds.length) {
                dropped.getAndUpdate(v -> v == Integer.MAX_VALUE ? v : v + 1);
                return;
            }
            if (cursor.compareAndSet(index, index + 1)) break;
        }
        siteIds[index] = siteId;
        outcomes[index] = failed ? 1 : 0;
        durations[index] = delta;
        // Release publication last. Readers acquire from published.
        published.set(index, PHASE_CALL);
    }

    int count() {
        return Math.min(Math.max(cursor.get(), 0), siteIds.length);
    }

    int droppedCount() {
        return dropped.get();
    }

    /**
     * Explicit cold-path diagnostic receipt only. Never called on a guest
     * request, scheduler or actor carrier. This is not OTLP, and is never
     * enabled automatically from an OpenTelemetry environment variable.
     */
    void dumpNumericReceipt(PrintWriter out) {
        Objects.requireNonNull(out, "out");
        out.print("{\"schema\":\"atto-internal-calls.v0\",\"kind\":\"begin\",\"capacity\":");
        out.print(siteIds.length);
        out.println("}");
        int complete = 0;
        int limit = count();
        for (int i = 0; i < limit; i++) {
            if (published.get(i) != PHASE_CALL) continue;
            out.print("{\"schema\":\"atto-internal-calls.v0\",\"kind\":\"call\",\"site_id\":");
            out.print(siteIds[i]);
            out.print(",\"phase_id\":100,\"duration_ns\":");
            out.print(durations[i]);
            out.print(",\"outcome\":");
            out.print(outcomes[i]);
            out.println("}");
            complete++;
        }
        out.print("{\"schema\":\"atto-internal-calls.v0\",\"kind\":\"end\",\"recorded\":");
        out.print(complete);
        out.print(",\"dropped\":");
        out.print(dropped.get());
        out.println("}");
        out.flush();
    }

    /** Stable numeric site tag for the first slice. No string allocation on
     * the OFF path; names are hashed only after the admission gate succeeds.
     * The shared cross-backend compiler registry will replace this provisional
     * hash before promoting a public stable probe ABI.
     */
    public static int internalSiteId(int codeUnitHash, String callableName, int kind) {
        int hash = 0x811c9dc5;
        hash = (hash ^ codeUnitHash) * 0x01000193;
        hash = (hash ^ kind) * 0x01000193;
        for (int i = 0; i < callableName.length(); i++) {
            hash = (hash ^ callableName.charAt(i)) * 0x01000193;
        }
        return hash == 0 ? 1 : hash;
    }
}
