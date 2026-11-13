package dev.oreslang.runtime;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host-owned automatic trace sites for the existing source-task lowering.
 * This is deliberately NOT a guest API or an OTLP exporter. A site is selected
 * from checked source declarations; no guest-supplied trace handle is admitted.
 * The trace scope belongs to the heap-owned SourceTask, never the carrier.
 */
public final class AutoFunctionTraceV1 {
    public static final class Scope {
        private final AutoFunctionTraceV1 owner;
        private final long executionId;
        private final TraceContextV1 parent;
        private final TraceContextV1 child;
        private final ScopedTelemetryProbe probe;
        private boolean finished;

        private Scope(AutoFunctionTraceV1 owner, long executionId,
                TraceContextV1 parent, TraceContextV1 child,
                ScopedTelemetryProbe probe) {
            this.owner = owner;
            this.executionId = executionId;
            this.parent = parent;
            this.child = child;
            this.probe = probe;
        }

        /** Immutable context may be propagated; the Scope itself may not. */
        public TraceContextV1 context() { return child; }
        public TraceContextV1 parent() { return parent; }
    }

    public record Counts(long completed, long suspended, long dropped) { }

    /** One immutable terminal receipt; mutable Scope never leaves its task. */
    public record Receipt(TraceContextV1 context, TraceContextV1 parent,
                          ScopedTelemetryProbe.Completion completion) { }

    private static final int MAX_RECEIPTS = 1024;

    private final TraceInstrumentationControlV1 policy;
    private final SecureRandom entropy;
    private final ConcurrentLinkedQueue<Receipt> receipts;
    private final AtomicInteger reservedReceipts = new AtomicInteger();
    private final AtomicLong nextExecutionId = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong suspended = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private AutoFunctionTraceV1(TraceInstrumentationControlV1.BuildMode buildMode,
            boolean active) {
        this.policy = new TraceInstrumentationControlV1(buildMode);
        // No clock, random generator or span-guard allocation when disabled.
        this.entropy = active ? new SecureRandom() : null;
        this.receipts = active ? new ConcurrentLinkedQueue<>() : null;
        if (active) policy.configure(TraceInstrumentationControlV1.CALL);
    }

    /**
     * Bootstrap-only opt-in while compiler build-mode flags are being lowered.
     * Application arguments originate from the embedding host, not guest code.
     * A sealed native AOT none build must still physically omit optional sites.
     */
    public static AutoFunctionTraceV1 fromApplicationArguments(String[] args,
            boolean adversarial) {
        Objects.requireNonNull(args, "args");
        TraceInstrumentationControlV1.BuildMode mode =
                TraceInstrumentationControlV1.BuildMode.NONE;
        boolean active = false;
        for (String arg : args) {
            if (arg.startsWith("--instrument=")) {
                mode = switch (arg.substring("--instrument=".length())) {
                    case "none" -> TraceInstrumentationControlV1.BuildMode.NONE;
                    case "boundaries" -> TraceInstrumentationControlV1.BuildMode.BOUNDARIES;
                    case "calls" -> TraceInstrumentationControlV1.BuildMode.CALLS;
                    case "full" -> TraceInstrumentationControlV1.BuildMode.FULL;
                    case "adaptive" -> TraceInstrumentationControlV1.BuildMode.ADAPTIVE;
                    default -> throw new IllegalArgumentException("unknown instrument build mode");
                };
            } else if (arg.startsWith("--ores-trace-calls=")) {
                String option = arg.substring("--ores-trace-calls=".length());
                if (!"true".equals(option) && !"false".equals(option)) {
                    throw new IllegalArgumentException("trace calls flag must be boolean");
                }
                active = "true".equals(option);
            }
        }
        if (active && adversarial) {
            throw new SecurityException("automatic guest tracing requires a trusted host");
        }
        if (active && mode != TraceInstrumentationControlV1.BuildMode.CALLS
                && mode != TraceInstrumentationControlV1.BuildMode.FULL) {
            throw new IllegalArgumentException(
                    "automatic function tracing requires compiled calls/full sites");
        }
        return new AutoFunctionTraceV1(mode, active);
    }

    /** The disabled hot path is one volatile read, with no heap/clock work. */
    public boolean enabled() {
        return policy.enabled(TraceInstrumentationControlV1.CALL);
    }

    /**
     * Deterministic provisional source-declaration site ID. Cross-backend
     * build manifests must replace this hash with collision-checked IDs.
     */
    public static int sourceSiteId(String codeUnit, String symbol, int arity) {
        Objects.requireNonNull(codeUnit, "codeUnit");
        Objects.requireNonNull(symbol, "symbol");
        if (arity < 0) throw new IllegalArgumentException("negative source arity");
        int hash = 0x811c9dc5;
        String key = codeUnit + "::" + symbol + "/" + arity;
        for (int i = 0; i < key.length(); i++) {
            hash = (hash ^ key.charAt(i)) * 0x01000193;
        }
        hash &= Integer.MAX_VALUE;
        return hash == 0 ? 1 : hash;
    }

    /** Called once, on the first logical turn, before the guest body runs. */
    public Scope begin(int siteId, TraceContextV1 inherited) {
        if (!enabled()) return null;
        if (siteId <= 0) throw new IllegalArgumentException("invalid compiler site id");
        long execution = nextExecutionId.incrementAndGet();
        if (execution <= 0) throw new IllegalStateException("trace execution IDs exhausted");
        long high = inherited == null ? nonzeroRandom() : inherited.traceHigh();
        long low = inherited == null ? entropy.nextLong() : inherited.traceLow();
        long childSpan = nonzeroRandom();
        if (inherited != null) {
            while (childSpan == inherited.spanId()) childSpan = nonzeroRandom();
        }
        TraceContextV1 child = new TraceContextV1(high, low, childSpan,
                inherited == null ? 0 : inherited.flags());
        // execution ID is never supplied by source, and one SourceTask owns it.
        ScopedTelemetryProbe probe = ScopedTelemetryProbe.enter(true, execution, 1, siteId);
        return new Scope(this, execution, inherited, child, probe);
    }

    private long nonzeroRandom() {
        long id;
        do { id = entropy.nextLong(); } while (id == 0);
        return id;
    }

    /** Host bookkeeping must never affect task results or carrier scheduling. */
    public void suspend(Scope scope) {
        if (scope == null) return;
        if (scope.owner != this || scope.finished || !scope.probe.suspend(scope.executionId, 1)) {
            dropped.incrementAndGet();
        } else {
            suspended.incrementAndGet();
        }
    }

    public void resume(Scope scope) {
        if (scope == null) return;
        if (scope.owner != this || scope.finished || !scope.probe.resume(scope.executionId, 1)) {
            dropped.incrementAndGet();
        }
    }

    public void finish(Scope scope, boolean cancelled, boolean failed) {
        if (scope == null) return;
        if (scope.owner != this || scope.finished) {
            dropped.incrementAndGet();
            return;
        }
        scope.finished = true;
        ScopedTelemetryProbe.Outcome outcome = cancelled
                ? ScopedTelemetryProbe.Outcome.CANCELLED
                : failed ? ScopedTelemetryProbe.Outcome.ERROR
                         : ScopedTelemetryProbe.Outcome.OK;
        ScopedTelemetryProbe.Completion completion =
                scope.probe.finish(scope.executionId, 1, outcome);
        if (completion == null) {
            dropped.incrementAndGet();
            return;
        }
        completed.incrementAndGet();
        // Bounded CAS reservation and a nonblocking queue: no carrier lock,
        // unbounded spin, guest callback or exporter I/O. Drop on contention.
        boolean reserved = false;
        for (int tries = 0; tries < 4; tries++) {
            int pending = reservedReceipts.get();
            if (pending >= MAX_RECEIPTS) break;
            if (reservedReceipts.compareAndSet(pending, pending + 1)) {
                reserved = true;
                break;
            }
        }
        if (reserved) {
            receipts.offer(new Receipt(scope.child, scope.parent, completion));
        } else {
            dropped.incrementAndGet();
        }
    }

    /** Host-only drain point; an exporter must run off-carrier. */
    public Receipt pollReceipt() {
        if (receipts == null) return null;
        Receipt receipt = receipts.poll();
        if (receipt != null) reservedReceipts.decrementAndGet();
        return receipt;
    }

    public Counts counts() {
        return new Counts(completed.get(), suspended.get(), dropped.get());
    }
}
