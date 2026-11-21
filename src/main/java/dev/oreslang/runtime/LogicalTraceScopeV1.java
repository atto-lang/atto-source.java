package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, task-owned trace-scope stack for a single logical execution.
 *
 * This is internal runtime scaffolding, not a guest-visible capability or an
 * automatic binding to the AttoLang evaluator. The scheduler must construct
 * and retain one instance per authenticated execution and never infer its
 * owner from the carrier thread. An immutable TraceContext may cross mail,
 * but Scope and Continuation tickets must stay with their originating owner.
 * No telemetry exporter, guest callback or network I/O runs here.
 */
final class LogicalTraceScopeV1 {
    static final int MAX_DEPTH = 256;

    static final class Scope {
        // Detach references at terminalization: callers may retain stale
        // Scope handles, but they must not pin the owning actor or ancestors.
        // Mutations are serialized on the enclosing LogicalTraceScopeV1 lock.
        private LogicalTraceScopeV1 owner;
        private Scope parent;
        private final TraceContextV1 context;
        private ScopedTelemetryProbe probe;
        private boolean finished;

        private Scope(LogicalTraceScopeV1 owner, Scope parent,
                TraceContextV1 context, ScopedTelemetryProbe probe) {
            this.owner = owner;
            this.parent = parent;
            this.context = context;
            this.probe = probe;
        }

        /** Package-private witness for deterministic cleanup tests only. */
        boolean detached() {
            return finished && owner == null && parent == null && probe == null;
        }

        private void detach() {
            finished = true;
            owner = null;
            parent = null;
            probe = null;
        }
    }

    /** Single-consumption, owner-fenced suspension receipt. */
    static final class Continuation {
        private LogicalTraceScopeV1 owner;
        private final long generation;
        private final long serial;
        private Scope top;
        private boolean consumed;

        private Continuation(LogicalTraceScopeV1 owner, long generation,
                long serial, Scope top) {
            this.owner = owner;
            this.generation = generation;
            this.serial = serial;
            this.top = top;
        }
    }

    private final long executionId;
    private final long generation;
    private final int maxDepth;
    private final boolean enabled;
    private final TraceContextV1 incomingContext;
    private Scope top;
    private Continuation pending;
    private int depth;
    private long nextSerial;
    private boolean suspended;
    private boolean retired;

    LogicalTraceScopeV1(long executionId, long generation,
            TraceContextV1 incomingContext, boolean enabled, int maxDepth) {
        if (executionId <= 0 || generation <= 0 || maxDepth < 1 || maxDepth > MAX_DEPTH) {
            throw new IllegalArgumentException("invalid logical trace owner or depth budget");
        }
        this.executionId = executionId;
        this.generation = generation;
        this.incomingContext = incomingContext;
        this.enabled = enabled;
        this.maxDepth = maxDepth;
    }

    /** Immutable propagation snapshot, never the owning mutable Scope. */
    synchronized TraceContextV1 context() {
        if (retired) return null;
        return top == null ? incomingContext : top.context;
    }

    /**
     * Trusted compiler/runtime entry only. Returns null without clock reads or
     * scoped-guard allocation when disabled. The caller must mint a fresh span
     * ID and keep the trace ID consistent with the existing parent context.
     */
    synchronized Scope enter(int probeId, TraceContextV1 child) {
        if (retired || suspended) throw new IllegalStateException("trace execution is not active");
        if (!enabled) return null;
        Objects.requireNonNull(child, "child trace context");
        if (depth >= maxDepth) throw new IllegalStateException("trace scope depth exceeded");
        TraceContextV1 parent = context();
        if (parent != null && (parent.traceHigh() != child.traceHigh()
                || parent.traceLow() != child.traceLow()
                || parent.spanId() == child.spanId())) {
            throw new IllegalArgumentException("trace child must use parent trace ID and fresh span ID");
        }
        ScopedTelemetryProbe probe = ScopedTelemetryProbe.enter(
                true, executionId, generation, probeId);
        Scope next = new Scope(this, top, child, probe);
        top = next;
        depth++;
        return next;
    }

    /** Captures the *same* logical guard stack; no new span on resumption. */
    synchronized Continuation suspend() {
        if (retired || suspended) throw new IllegalStateException("trace already suspended or retired");
        if (!enabled) return null;
        if (nextSerial == Long.MAX_VALUE) throw new IllegalStateException("trace resume serial exhausted");
        for (Scope s = top; s != null; s = s.parent) {
            if (!s.probe.suspend(executionId, generation)) {
                throw new IllegalStateException("inconsistent trace suspend state");
            }
        }
        suspended = true;
        pending = new Continuation(this, generation, ++nextSerial, top);
        return pending;
    }

    /** Accept one ticket from this execution, even on a different carrier. */
    synchronized boolean resume(Continuation ticket) {
        if (!enabled) return ticket == null && !retired;
        if (retired || !suspended || ticket == null || pending != ticket
                || ticket.owner != this || ticket.consumed
                || ticket.generation != generation || ticket.serial != nextSerial
                || ticket.top != top) return false;
        for (Scope s = top; s != null; s = s.parent) {
            if (!s.probe.resume(executionId, generation)) {
                throw new IllegalStateException("inconsistent trace resume state");
            }
        }
        ticket.consumed = true;
        ticket.top = null;
        ticket.owner = null;
        pending = null;
        suspended = false;
        return true;
    }

    /** Strict lexical LIFO scope exit, including returns and caught errors. */
    synchronized ScopedTelemetryProbe.Completion exit(
            Scope scope, ScopedTelemetryProbe.Outcome outcome) {
        if (!enabled) return null;
        if (scope == null || scope.owner != this || scope.finished || retired) return null;
        if (suspended) throw new IllegalStateException("cannot exit a suspended trace scope");
        if (top != scope) throw new IllegalStateException("non-LIFO trace scope cleanup");
        Objects.requireNonNull(outcome, "outcome");
        top = scope.parent;
        depth--;
        // The scope may be retained by stale external code after lexical exit.
        // Clear ownership links even if a diagnostic clock fails unexpectedly.
        try {
            return scope.probe.finish(executionId, generation, outcome);
        } finally {
            scope.detach();
        }
    }

    /**
     * Runtime-owned STOP/cancel boundary. Revokes pending continuations, then
     * completes every remaining guard once, innermost first. A retry produces
     * no more receipts. The caller may offer receipts to a bounded collector.
     */
    synchronized List<ScopedTelemetryProbe.Completion> retire(
            ScopedTelemetryProbe.Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (retired) return List.of();
        retired = true;
        suspended = false;
        if (pending != null) {
            pending.consumed = true;
            pending.top = null;
            pending.owner = null;
        }
        pending = null;
        if (!enabled) return List.of();
        ArrayList<ScopedTelemetryProbe.Completion> receipts = new ArrayList<>(depth);
        while (top != null) {
            Scope item = top;
            top = item.parent;
            depth--;
            try {
                ScopedTelemetryProbe.Completion receipt =
                        item.probe.finish(executionId, generation, outcome);
                if (receipt != null) receipts.add(receipt);
            } finally {
                // Don't pin the entire older actor-generation scope chain
                // through an outside reference to a retired inner handle.
                item.detach();
            }
        }
        return List.copyOf(receipts);
    }

    synchronized int depth() { return depth; }
    synchronized boolean isRetired() { return retired; }
}
