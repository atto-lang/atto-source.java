package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trusted-host scope lifecycle core for a future compiler-owned Atto SpanScope.
 *
 * A SpanToken has no public constructor, mutators or close method. It must
 * never be projected into a guest value, a mailbox, or a source-level class.
 * The compiler/evaluator integration and runtime authorization gate are NOT
 * implemented by this standalone lifecycle primitive.
 *
 * A Carrier belongs to one logical continuation, not to a Java thread.
 * The same carrier may resume on another thread; stack mutations are guarded
 * by the carrier monitor and cleanup is LIFO. Callers must nevertheless
 * serialize the guest continuation itself.
 */
public final class NativeTelemetryScopes {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_OPERATION_UTF8_BYTES = 256;

    public static final class Carrier {
        private final ArrayDeque<SpanToken> stack = new ArrayDeque<>();
        // Preallocated at continuation creation: the disabled fast path
        // reuses this carrier-bound singleton without token allocation.
        private final SpanToken disabledToken = new SpanToken(this, 0L, null, true);
        private volatile boolean retired;

        private Carrier() { }
    }

    /** Opaque compiler-owned token; guest code must never receive it. */
    public static final class SpanToken {
        private final Carrier carrier;
        private final long startNanos;
        private final String operation;
        private final boolean disabled;
        private boolean closed;
        private int observerCalls;

        private SpanToken(Carrier carrier, long startNanos,
                          String operation, boolean disabled) {
            this.carrier = carrier;
            this.startNanos = startNanos;
            this.operation = operation;
            this.disabled = disabled;
        }
    }

    public record Snapshot(long started, long ended, long active,
                           long cumulativeNanos, long rejected,
                           long observerCalls, long dropped) { }

    private final AtomicBoolean enabled = new AtomicBoolean();
    private final AtomicLong started = new AtomicLong();
    private final AtomicLong ended = new AtomicLong();
    private final AtomicLong active = new AtomicLong();
    private final AtomicLong cumulativeNanos = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong observerCalls = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    /** Allocate once when a trusted runtime creates a logical continuation. */
    public Carrier createCarrierForTrustedRuntime() {
        return new Carrier();
    }

    /**
     * Must be called only by an authorized host controller. This method is
     * not exposed as a guest intrinsic; authorization/Truffle attach is a
     * separate integration gate.
     */
    public void setEnabledFromTrustedHost(boolean recording) {
        enabled.set(recording);
    }

    public boolean isEnabledForTrustedHost() {
        return enabled.get();
    }

    /**
     * Start a compiler-owned scope on its logical continuation.
     * Disabled path never reads a clock or allocates a token.
     */
    public SpanToken beginFromTrustedRuntime(Carrier carrier, String operation) {
        Objects.requireNonNull(carrier, "continuation carrier");
        requireOperationName(operation);
        if (carrier.retired) {
            rejected.incrementAndGet();
            throw new IllegalStateException("retired telemetry continuation");
        }
        if (!enabled.get()) {
            return carrier.disabledToken;
        }
        synchronized (carrier) {
            if (carrier.retired) {
                rejected.incrementAndGet();
                throw new IllegalStateException("telemetry continuation retired");
            }
            if (carrier.stack.size() >= MAX_DEPTH) {
                // Probe budget is not application authority. Keep the guest
                // result unchanged and close this nested scope as a no-op.
                dropped.incrementAndGet();
                return carrier.disabledToken;
            }
            SpanToken token = new SpanToken(carrier, System.nanoTime(), operation, false);
            carrier.stack.push(token);
            started.incrementAndGet();
            active.incrementAndGet();
            return token;
        }
    }

    /**
     * Compiler-only cleanup; neither guest code nor the token can close
     * itself. A failed close leaves the real owner stack intact.
     */
    public void endFromTrustedRuntime(Carrier carrier, SpanToken token) {
        Objects.requireNonNull(carrier, "continuation carrier");
        Objects.requireNonNull(token, "opaque span token");
        if (token.disabled) {
            if (token.carrier != carrier || carrier.retired) {
                rejected.incrementAndGet();
                throw new IllegalStateException("retired telemetry continuation");
            }
            return;
        }
        synchronized (carrier) {
            if (token.carrier != carrier || token.closed
                    || carrier.retired || carrier.stack.peek() != token) {
                rejected.incrementAndGet();
                throw new IllegalStateException(
                        "invalid, closed, out-of-order or cross-continuation telemetry token");
            }
            carrier.stack.pop();
            token.closed = true;
            finish(token);
        }
    }

    /**
     * Compiler-dispatched observer. Only string-bearing methods are admitted
     * in this restricted phase; the bounded counter proves dispatch, while
     * full attribute/event storage and OTLP export remain separate work.
     * A guest cannot obtain the token or call this Java method directly.
     */
    public void observeFromTrustedRuntime(
            Carrier carrier, SpanToken token, String method, java.util.List<?> values) {
        Objects.requireNonNull(carrier, "continuation carrier");
        Objects.requireNonNull(token, "scope token");
        Objects.requireNonNull(values, "observer values");
        final int expected = "set_attribute".equals(method) ? 2 : 1;
        if (!java.util.Set.of("set_attribute", "add_event", "record_error", "set_status")
                .contains(method) || values.size() != expected) {
            rejected.incrementAndGet();
            throw new IllegalArgumentException("invalid native SpanScope observer");
        }
        for (Object value : values) {
            if (!(value instanceof String text)) {
                rejected.incrementAndGet();
                throw new IllegalArgumentException("native SpanScope observer requires strings");
            }
            if (text.length() > 1024) {
                // Oversized telemetry metadata must never affect the guest's
                // ordinary result, whether recording is on or off.
                dropped.incrementAndGet();
                return;
            }
        }
        synchronized (carrier) {
            if (token.carrier != carrier || carrier.retired || token.closed
                    || (!token.disabled && carrier.stack.peek() != token)) {
                rejected.incrementAndGet();
                throw new IllegalStateException("invalid native SpanScope observer owner");
            }
            if (token.disabled) return;
            if (token.observerCalls >= 256) {
                dropped.incrementAndGet();
                return;
            }
            token.observerCalls++;
            observerCalls.incrementAndGet();
        }
    }

    /**
     * Task/actor cancellation hook: end all active scopes in strict LIFO
     * order and forbid reuse. Does not invoke application code.
     */
    public void retireContinuationFromTrustedRuntime(Carrier carrier) {
        Objects.requireNonNull(carrier, "continuation carrier");
        synchronized (carrier) {
            if (carrier.retired) return;
            carrier.retired = true;
            while (!carrier.stack.isEmpty()) {
                SpanToken token = carrier.stack.pop();
                if (token.closed) {
                    throw new IllegalStateException("closed token retained on carrier stack");
                }
                token.closed = true;
                finish(token);
            }
        }
    }

    public Snapshot snapshotForTrustedHost() {
        return new Snapshot(started.get(), ended.get(), active.get(),
                cumulativeNanos.get(), rejected.get(), observerCalls.get(),
                dropped.get());
    }

    private void finish(SpanToken token) {
        // An enabled scope must complete even when the controller disables
        // recording after its begin. Disabled scopes never reach this path.
        long elapsed = Math.max(0L, System.nanoTime() - token.startNanos);
        cumulativeNanos.getAndUpdate(current -> {
            if (Long.MAX_VALUE - current < elapsed) return Long.MAX_VALUE;
            return current + elapsed;
        });
        active.decrementAndGet();
        ended.incrementAndGet();
    }

    /**
     * Pure compiler/runtime shared validation. No encoding allocation and no
     * lossy replacement of malformed UTF-16 surrogates are permitted.
     */
    public static void validateOperationLiteral(String operation) {
        requireOperationName(operation);
    }

    private static void requireOperationName(String operation) {
        Objects.requireNonNull(operation, "span operation");
        if (operation.isEmpty()) {
            throw new IllegalArgumentException("span operation cannot be empty");
        }
        int bytes = 0;
        for (int index = 0; index < operation.length();) {
            char ch = operation.charAt(index++);
            int codepoint = ch;
            if (Character.isHighSurrogate(ch)) {
                if (index == operation.length()
                        || !Character.isLowSurrogate(operation.charAt(index))) {
                    throw new IllegalArgumentException("invalid UTF-16 span operation");
                }
                codepoint = Character.toCodePoint(ch, operation.charAt(index++));
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("invalid UTF-16 span operation");
            }
            bytes += codepoint <= 0x7F ? 1
                    : codepoint <= 0x7FF ? 2
                    : codepoint <= 0xFFFF ? 3 : 4;
            if (bytes > MAX_OPERATION_UTF8_BYTES) {
                throw new IllegalArgumentException(
                        "span operation exceeds 256 UTF-8 bytes");
            }
        }
    }
}
