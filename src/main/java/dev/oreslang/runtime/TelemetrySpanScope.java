package dev.oreslang.runtime;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compiler-owned lexical telemetry scope for `using telemetry.span(...) as span`.
 *
 * <p>Scope identity belongs to the logical task. The scheduler saves/restores
 * its immutable {@link TraceContext} around await/cooperate dispatches.
 * End/restore is part of source lexical cleanup, never of a producer Future,
 * a carrier-thread callback or an instrumentation/exporter worker.</p>
 *
 * <p>Recording is disabled by default. The host can temporarily activate
 * existing span sites; this class does not generate probes, JIT-recompile
 * guest code, send telemetry or grant the guest an instrumentation authority.</p>
 */
public final class TelemetrySpanScope implements AutoCloseable {
    private static final int MAX_NAME = 128;
    private static final int MAX_ATTRIBUTES = 16;
    private static final int MAX_ATTRIBUTE_KEY = 80;
    private static final int MAX_ATTRIBUTE_VALUE = 256;
    private static final String HEX = "0123456789abcdef";
    private static final AtomicReference<Policy> POLICY =
            new AtomicReference<>(new Policy(false, 0, 0L, ""));
    private static final AtomicLong STARTED = new AtomicLong();
    private static final AtomicLong ENDED = new AtomicLong();

    private final String name;
    /** Stable logical task/actor execution identity, not physical carrier. */
    private final Object ownerDomain;
    private final TraceContext parent;
    private final TraceContext active;
    private final boolean recording;
    private final long startNanos;
    /** Frozen at lexical close; never compute a closed span against the wall clock. */
    private long finishedDurationNanos;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final Map<String, String> attributes = new LinkedHashMap<>();

    private record Policy(boolean enabled, int samplePercent,
                          long expiresNanos, String prefix) {
        boolean covers(String spanName) {
            return enabled && (prefix.isEmpty() || spanName.startsWith(prefix))
                    // Unsigned elapsed-time check: expiry is relative to a
                    // monotonic deadline and no timer/carrier work is needed.
                    && System.nanoTime() - expiresNanos < 0L;
        }
    }

    private TelemetrySpanScope(String name, TraceContext parent,
                               TraceContext active, boolean recording) {
        this.name = name;
        this.ownerDomain = currentOwnerDomain();
        this.parent = parent;
        this.active = active;
        this.recording = recording;
        this.startNanos = recording ? System.nanoTime() : 0L;
    }

    private static final class RandomId {
        private static final SecureRandom RANDOM = new SecureRandom();
    }

    private static void requireHostControl() {
        if (OresScheduler.currentTaskDomain() != null
                || OresScheduler.isSchedulerCarrierThread()
                || ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()
                || ActorRuntime.isActorCarrierThread()) {
            throw new SecurityException(
                    "telemetry instrumentation configuration requires a trusted host control turn");
        }
    }

    /** Trusted host operation only. There is intentionally no guest-facing toggle. */
    public static void enableExistingSites(int samplePercent, long durationMillis,
                                           String spanNamePrefix) {
        requireHostControl();
        if (samplePercent < 0 || samplePercent > 100) {
            throw new IllegalArgumentException("sample percent must be 0..100");
        }
        if (durationMillis <= 0 || durationMillis > 86_400_000L) {
            throw new IllegalArgumentException("duration must be 1..86400000 ms");
        }
        Objects.requireNonNull(spanNamePrefix, "spanNamePrefix");
        if (spanNamePrefix.length() > MAX_NAME) {
            throw new IllegalArgumentException("span prefix too long");
        }
        requireSafeName(spanNamePrefix, true);
        long nanos = Math.multiplyExact(durationMillis, 1_000_000L);
        if (samplePercent > 0) {
            // Initialize the random provider on a trusted host control turn,
            // not the first actor carrier entering a newly enabled span.
            byte[] warm = new byte[16];
            RandomId.RANDOM.nextBytes(warm);
            java.util.Arrays.fill(warm, (byte) 0);
        }
        POLICY.set(new Policy(true, samplePercent, System.nanoTime() + nanos,
                spanNamePrefix));
    }

    public static void disableExistingSites() {
        requireHostControl();
        POLICY.set(new Policy(false, 0, 0L, ""));
    }

    /** Called only by compiler-lowered lexical using, exactly once per entry. */
    private static void requireSafeName(String name, boolean allowEmpty) {
        if (name == null || (!allowEmpty && name.isBlank()) || name.length() > MAX_NAME) {
            throw new IllegalArgumentException("telemetry span name must have 1..128 characters");
        }
        // No control, unpaired surrogate or directional-formatting characters
        // can enter telemetry labels (even when sampling is disabled).
        for (int i = 0; i < name.length();) {
            int point = name.codePointAt(i);
            if (Character.isISOControl(point)
                    || Character.getType(point) == Character.FORMAT
                    || (point <= Character.MAX_VALUE
                        && Character.isSurrogate((char) point))) {
                throw new IllegalArgumentException("telemetry span name contains unsafe characters");
            }
            i += Character.charCount(point);
        }
    }

    public static TelemetrySpanScope open(String name) {
        requireSafeName(name, false);
        TraceContext parent = TraceContext.current();
        Policy policy = POLICY.get();
        // Keep inherited trace identity even when local recording is off.
        if (!policy.covers(name)) return new TelemetrySpanScope(name, parent, parent, false);
        if (parent != null && !parent.sampled()) {
            return new TelemetrySpanScope(name, parent, parent, false);
        }
        boolean sample = policy.samplePercent() == 100
                || (policy.samplePercent() > 0
                    && java.util.concurrent.ThreadLocalRandom.current().nextInt(100)
                            < policy.samplePercent());
        if (!sample) return new TelemetrySpanScope(name, parent, parent, false);
        TraceContext next;
        if (parent == null) {
            next = TraceContext.root(randomId(16), randomId(8), true);
        } else {
            String childId;
            do { childId = randomId(8); }
            while (childId.equals(parent.spanId()));
            next = parent.child(childId);
        }
        TraceContext.install(next);
        STARTED.incrementAndGet();
        return new TelemetrySpanScope(name, parent, next, true);
    }

    private static String randomId(int bytes) {
        byte[] buffer = new byte[bytes];
        char[] result = new char[bytes * 2];
        do {
            RandomId.RANDOM.nextBytes(buffer);
            boolean nonzero = false;
            for (int i = 0; i < bytes; i++) {
                int b = buffer[i] & 0xff;
                nonzero |= b != 0;
                result[i * 2] = HEX.charAt(b >>> 4);
                result[i * 2 + 1] = HEX.charAt(b & 0xf);
            }
            if (nonzero) return new String(result);
        } while (true);
    }

    private static Object currentOwnerDomain() {
        Object task = OresScheduler.currentTaskDomain();
        if (task != null) return task;
        Object actor = ActorRuntime.currentActorExecutionDomain();
        return actor != null ? actor : Thread.currentThread();
    }

    private void requireOwner() {
        if (currentOwnerDomain() != ownerDomain) {
            throw new IllegalStateException(
                    "telemetry span handle belongs to a different logical execution");
        }
    }

    /** Bounded safe scalar attributes; neither the guest body nor exporter runs here. */
    public void attribute(String key, Object value) {
        requireOwner();
        if (ended.get()) {
            throw new IllegalStateException("telemetry span is closed");
        }
        if (!recording) return;
        if (TraceContext.current() != active) {
            throw new IllegalStateException("span attributes require the owning logical task scope");
        }
        if (key == null || key.isBlank() || key.length() > MAX_ATTRIBUTE_KEY
                || !key.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("telemetry attribute key is invalid");
        }
        // Never record secrets or wire bodies even when tracing is enabled.
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("password") || lower.contains("authorization")
                || lower.contains("cookie") || lower.contains("secret")
                || lower.contains("token") || lower.contains("body")) {
            throw new SecurityException("sensitive attribute keys are not allowed");
        }
        // Do not invoke an arbitrary Number.toString() on an actor carrier.
        // Number is extensible and may block, throw or expose foreign state.
        // Only trusted, final boxed primitives are allowed for this v1 ABI.
        boolean scalar = value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double;
        if (!scalar) {
            throw new IllegalArgumentException("telemetry attribute must be a boxed primitive scalar");
        }
        if (value instanceof Double n && !Double.isFinite(n)
                || value instanceof Float n && !Float.isFinite(n)) {
            throw new IllegalArgumentException("telemetry attribute must be finite");
        }
        String safe = String.valueOf(value);
        if (safe.length() > MAX_ATTRIBUTE_VALUE) {
            throw new IllegalArgumentException("telemetry attribute value is too long");
        }
        if (!attributes.containsKey(key) && attributes.size() >= MAX_ATTRIBUTES) {
            return; // bounded drop, never expand an untrusted actor's allocation.
        }
        attributes.put(key, safe);
    }

    public String name() { requireOwner(); return name; }
    public boolean recording() { requireOwner(); return recording; }

    /** Read-only trace identifiers for propagation and diagnostic fixtures. */
    public String traceId() {
        requireOwner();
        return active == null ? "" : active.traceId();
    }

    public String spanId() {
        requireOwner();
        return active == null ? "" : active.spanId();
    }

    /** Host diagnostic copy. No guest API exposes the mutable attributes map. */
    public Map<String, String> attributesSnapshot() {
        requireOwner();
        return Map.copyOf(attributes);
    }

    /** Return the last span duration for optional diagnostic sinks. */
    public long elapsedNanos() {
        requireOwner();
        if (!recording) return 0L;
        return ended.get()
                ? finishedDurationNanos
                : Math.max(0L, System.nanoTime() - startNanos);
    }

    /** Idempotent close: normal completion and cancellation cleanup can race. */
    @Override
    public void close() {
        if (ended.get()) return;
        requireOwner();
        if (recording && TraceContext.current() != active) {
            throw new IllegalStateException("span ended outside its owning logical task");
        }
        if (!ended.compareAndSet(false, true)) return;
        if (recording) {
            finishedDurationNanos = Math.max(0L, System.nanoTime() - startNanos);
            TraceContext.install(parent);
            ENDED.incrementAndGet();
        }
    }

    /** Host-only, aggregate diagnostics. Does not export event content. */
    public static long startedCount() { return STARTED.get(); }
    public static long endedCount() { return ENDED.get(); }

    public boolean ended() { return ended.get(); }
}
