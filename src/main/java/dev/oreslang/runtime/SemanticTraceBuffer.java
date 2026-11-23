package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Separate semantic span staging queue for trusted automatic runtime hooks.
 *
 * This is a bounded, append-only, single-generation HOST STAGING buffer, not
 * an OpenTelemetry exporter, consumer, host diagnostic log, or guest API.
 * It records no source/user strings, headers, request bodies, credentials or
 * arbitrary object references. It uses numeric operation labels only.
 *
 * An actor-owned native .atto consumer and a verified bounded drain protocol
 * must replace this staging store before an actual OTLP end-to-end claim.
 */
public final class SemanticTraceBuffer {
    public static final int HTTP_SERVER = 201;
    public static final int HTTP_CLIENT = 200;
    public static final int ACTOR_RECEIVE = 501;

    public enum Kind {
        INTERNAL(1), SERVER(2), CLIENT(3);
        public final int otlpKind;
        Kind(int otlpKind) { this.otlpKind = otlpKind; }
    }

    public record Completed(
            TraceParent context,
            String parentSpanId,
            int operationId,
            Kind kind,
            long startUnixNanos,
            long endUnixNanos,
            int httpStatus,
            boolean failed) {
        public Completed {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(parentSpanId, "parentSpanId");
            Objects.requireNonNull(kind, "kind");
            if (operationId <= 0 || startUnixNanos <= 0
                    || endUnixNanos < startUnixNanos
                    || httpStatus < 0 || (httpStatus != 0 && httpStatus < 100)
                    || httpStatus > 599
                    || !validParent(parentSpanId, context.spanId())) {
                throw new IllegalArgumentException("invalid semantic span");
            }
        }
    }

    /** Enforce the same nonzero 64-bit lowercase W3C span-ID discipline
     * at the compiler/runtime staging boundary as at the wire boundary.
     * Only the empty string denotes a genuine root; it is NOT an all-zero ID.
     */
    private static boolean validParent(String parent, String currentSpanId) {
        if (parent.isEmpty()) return true;
        if (parent.length() != 16 || parent.equals(currentSpanId)) return false;
        boolean nonzero = false;
        for (int i = 0; i < parent.length(); i++) {
            char ch = parent.charAt(i);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) {
                return false;
            }
            if (ch != '0') nonzero = true;
        }
        return nonzero;
    }

    public static final class Scope {
        private final SemanticTraceBuffer owner;
        private final TraceParent context;
        private final String parentSpanId;
        private final int operationId;
        private final Kind kind;
        private final long startedUnixNanos;
        private final long startedMonotonic;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Scope(SemanticTraceBuffer owner, TraceParent context,
                      String parentSpanId, int operationId, Kind kind) {
            this.owner = owner;
            this.context = context;
            this.parentSpanId = parentSpanId;
            this.operationId = operationId;
            this.kind = kind;
            this.startedUnixNanos = System.currentTimeMillis() * 1_000_000L;
            this.startedMonotonic = System.nanoTime();
        }

        public TraceParent context() {
            return context;
        }

        /**
         * Single-shot nonblocking enqueue. No network, guest callback,
         * external logging or producer wait. An in-flight scope can end after
         * runtime tracing is disabled; it retains its initial admission.
         */
        public boolean finish(int httpStatus, boolean failed) {
            if (!finished.compareAndSet(false, true)) return false;
            long elapsed = System.nanoTime() - startedMonotonic;
            if (elapsed < 0) elapsed = 0;
            long end = elapsed > Long.MAX_VALUE - startedUnixNanos
                    ? Long.MAX_VALUE : startedUnixNanos + elapsed;
            owner.record(new Completed(context, parentSpanId, operationId,
                    kind, startedUnixNanos, end, httpStatus, failed));
            return true;
        }
    }

    private final AutoTraceRecorder gate;
    private final AtomicReferenceArray<Completed> slots;
    private final AtomicInteger cursor = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();

    public SemanticTraceBuffer(AutoTraceRecorder gate, int capacity) {
        this.gate = Objects.requireNonNull(gate, "gate");
        if (capacity < 1 || capacity > 65536) {
            throw new IllegalArgumentException("semantic trace capacity must be 1..65536");
        }
        this.slots = new AtomicReferenceArray<>(capacity);
    }

    /**
     * Return null without clock reads, sampling allocations or context
     * mutation when off or unsampled. The parent ID is already trusted,
     * validated or empty for a root.
     */
    public Scope begin(TraceParent current, String parentSpanId,
                       int operationId, Kind kind) {
        if (!gate.enabled() || current == null || !current.sampled()) return null;
        if (operationId <= 0 || kind == null || parentSpanId == null
                || !validParent(parentSpanId, current.spanId())) {
            throw new IllegalArgumentException("invalid semantic span admission");
        }
        return new Scope(this, current, parentSpanId, operationId, kind);
    }

    /** Null-safe lifecycle hook for disabled mode, and no failure masking. */
    public static void finish(Scope scope, int status, boolean failed) {
        if (scope == null) return;
        try {
            scope.finish(status, failed);
        } catch (RuntimeException ignored) {
            // Telemetry cannot perturb application progress or actor CONTROL.
        }
    }

    private void record(Completed completed) {
        while (true) {
            int index = cursor.get();
            if (index >= slots.length()) {
                dropped.getAndUpdate(v -> v == Integer.MAX_VALUE ? v : v + 1);
                return;
            }
            if (cursor.compareAndSet(index, index + 1)) {
                slots.set(index, completed);
                return;
            }
        }
    }

    /** Cold-path inspection only. Never invoke on an actor request carrier. */
    List<Completed> snapshot() {
        int count = Math.min(cursor.get(), slots.length());
        List<Completed> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Completed item = slots.get(i);
            if (item != null) out.add(item);
        }
        return List.copyOf(out);
    }

    public int recordedCount() {
        return Math.min(cursor.get(), slots.length());
    }

    public int droppedCount() {
        return dropped.get();
    }
}
