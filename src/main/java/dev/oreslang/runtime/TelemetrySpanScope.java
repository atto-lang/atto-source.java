package dev.oreslang.runtime;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    private final String name;
    private final TraceContext parent;
    private final TraceContext active;
    private final boolean recording;
    private final long startNanos;
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
        this.parent = parent;
        this.active = active;
        this.recording = recording;
        this.startNanos = recording ? System.nanoTime() : 0L;
    }

    private static final class RandomId {
        private static final SecureRandom RANDOM = new SecureRandom();
    }

    /** Trusted host operation only. There is intentionally no guest-facing toggle. */
    public static void enableExistingSites(int samplePercent, long durationMillis,
                                           String spanNamePrefix) {
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
        long nanos = Math.multiplyExact(durationMillis, 1_000_000L);
        POLICY.set(new Policy(true, samplePercent, System.nanoTime() + nanos,
                spanNamePrefix));
    }

    public static void disableExistingSites() {
        POLICY.set(new Policy(false, 0, 0L, ""));
    }

    /** Called only by compiler-lowered lexical using, exactly once per entry. */
    public static TelemetrySpanScope open(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME) {
            throw new IllegalArgumentException("telemetry span name must have 1..128 characters");
        }
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
        TraceContext next = parent == null
                ? TraceContext.root(randomId(16), randomId(8), true)
                : parent.child(randomId(8));
        TraceContext.install(next);
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

    /** Bounded safe scalar attributes; neither the guest body nor exporter runs here. */
    public void attribute(String key, Object value) {
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
        if (!(value instanceof String || value instanceof Number
                || value instanceof Boolean)) {
            throw new IllegalArgumentException("telemetry attribute must be scalar");
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

    public String name() { return name; }
    public boolean recording() { return recording; }

    /** Host diagnostic copy. No guest API exposes the mutable attributes map. */
    public Map<String, String> attributesSnapshot() {
        return Map.copyOf(attributes);
    }

    /** Return the last span duration for optional diagnostic sinks. */
    public long elapsedNanos() {
        if (!recording) return 0L;
        return Math.max(0L, System.nanoTime() - startNanos);
    }

    /** Idempotent close: normal completion and cancellation cleanup can race. */
    @Override
    public void close() {
        if (ended.get()) return;
        if (recording && TraceContext.current() != active) {
            throw new IllegalStateException("span ended outside its owning logical task");
        }
        if (!ended.compareAndSet(false, true)) return;
        if (recording) TraceContext.install(parent);
    }

    public boolean ended() { return ended.get(); }
}
