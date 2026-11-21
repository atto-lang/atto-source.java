package dev.oreslang.runtime;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host-only bridge from real ActorRuntime finalization into trusted supervisor
 * CONTROL ingress. Guest code never receives ChildBinding or escalation authority.
 */
final class SupervisorRuntimeBridge implements AutoCloseable {
    @FunctionalInterface
    interface ParentEscalation {
        /**
         * Must synchronously publish or durably hand off the authenticated fatal
         * event. Returning false is fatal to the bridge: a terminal event may
         * never be reported as handled when it was dropped.
         */
        boolean escalate(TrustedSupervisorInbox.FailureNotice notice);
    }

    private final ActorRuntime runtime;
    private final TrustedSupervisorInbox inbox;
    private final SupervisorDenseTable table;
    private final ParentEscalation escalation;
    private final Map<ActorRuntime.ActorRef<?>, TrustedSupervisorInbox.ChildBinding> bindings =
            new IdentityHashMap<>();
    private final Map<TrustedSupervisorInbox.ChildBinding, Long> childIdsByBinding =
            new IdentityHashMap<>();
    private final Map<TrustedSupervisorInbox.ChildBinding, SupervisorDenseTable.Handle> rowsByBinding =
            new IdentityHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong childIds = new AtomicLong();
    private boolean closed;
    private boolean escalatedToParent;
    private Throwable poison;

    private static final long CHILD_RUNNING = 1L;
    private static final long CHILD_FAILED = 2L;

    SupervisorRuntimeBridge(
            ActorRuntime runtime,
            TrustedSupervisorInbox inbox,
            SupervisorDenseTable table,
            ParentEscalation escalation) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.table = Objects.requireNonNull(table, "table");
        this.escalation = Objects.requireNonNull(escalation, "escalation");
    }

    /**
     * Register a verified local child capability and attach a runtime-owned
     * finalization callback. ActorRef generation is used as the incarnation fence.
     */
    synchronized void register(ActorRuntime.ActorRef<?> child) {
        checkHealthy();
        Objects.requireNonNull(child, "child");
        if (bindings.containsKey(child)) {
            throw new IllegalArgumentException("child already registered");
        }

        // Reuse ActorRuntime's own local-capability ownership validation. This
        // rejects foreign-runtime refs and already-finalized children before
        // any supervisor binding is minted.
        runtime.heapStatusFor(child);

        long childId = nextChildId();
        TrustedSupervisorInbox.ChildBinding binding =
                inbox.bindVerifiedChild(childId, child.generation());
        final SupervisorDenseTable.Handle row;
        try {
            row = table.add(
                    childId,
                    CHILD_RUNNING,
                    0L,
                    0L,
                    0L);
        } catch (RuntimeException | Error failure) {
            revokeIfLive(binding);
            throw failure;
        }

        bindings.put(child, binding);
        childIdsByBinding.put(binding, childId);
        rowsByBinding.put(binding, row);

        child.getDone().whenCompleteRuntime((ignored, doneFailure) ->
                onFinalized(child, binding, doneFailure));
    }

    private void onFinalized(
            ActorRuntime.ActorRef<?> child,
            TrustedSupervisorInbox.ChildBinding binding,
            Throwable doneFailure) {
        final Throwable failure = child.failure().orElse(doneFailure);
        final TrustedSupervisorInbox.Admission result;
        final TrustedSupervisorInbox.FailureNotice escalationNotice;
        final SupervisorDenseTable.Handle terminalRow;

        synchronized (this) {
            bindings.remove(child, binding);
            Long knownChildId = childIdsByBinding.remove(binding);
            SupervisorDenseTable.Handle row = rowsByBinding.get(binding);
            terminalRow = row;
            if (knownChildId == null || row == null) {
                if (closed) return;
                throw new IllegalStateException("trusted child identity/metadata unavailable");
            }

            if (closed || failure == null) {
                revokeIfLive(binding);
                rowsByBinding.remove(binding);
                eraseRowIfLive(row);
                return;
            }

            table.set(row, SupervisorDenseTable.Field.STATE, CHILD_FAILED);
            table.set(
                    row,
                    SupervisorDenseTable.Field.LAST_FAILURE_NANOS,
                    System.nanoTime());

            long seq = nextSequence();
            long faultCode = stableFaultCode(failure);

            /*
             * Keep the bridge lifecycle lock through trusted ingress admission.
             * close() uses the same bridge->inbox lock order, so it cannot revoke
             * this binding in the gap between validation and CONTROL publication.
             */
            result = inbox.offerTrustedPanic(binding, seq, faultCode);
            escalationNotice = result == TrustedSupervisorInbox.Admission.ESCALATE_TO_PARENT
                    ? new TrustedSupervisorInbox.FailureNotice(
                            knownChildId, child.generation(), seq, faultCode)
                    : null;
            if (escalationNotice != null) {
                escalatedToParent = true;
            }

            if (result == TrustedSupervisorInbox.Admission.ACCEPTED) {
                // The queued FailureNotice is detached value data. Retire the
                // live child authority and live metadata immediately.
                revokeIfLive(binding);
                rowsByBinding.remove(binding);
                eraseRowIfLive(row);
            } else if (result == TrustedSupervisorInbox.Admission.REJECTED) {
                IllegalStateException rejection = new IllegalStateException(
                        "runtime-authenticated finalized child failure was rejected");
                poison(rejection);
                throw rejection;
            }
        }

        // External parent routing must never run under the bridge/inbox locks.
        if (escalationNotice != null) {
            final boolean accepted;
            try {
                accepted = escalation.escalate(escalationNotice);
            } catch (VirtualMachineError | ThreadDeath fatal) {
                poison(fatal);
                throw fatal;
            } catch (Throwable failure) {
                poison(failure);
                throw new IllegalStateException(
                        "supervisor CONTROL parent escalation failed", failure);
            }
            if (!accepted) {
                IllegalStateException failure = new IllegalStateException(
                        "supervisor CONTROL overflow escalation was not durably accepted");
                poison(failure);
                throw failure;
            }
            synchronized (this) {
                rowsByBinding.remove(binding);
                eraseRowIfLive(terminalRow);
            }
        }
    }

    private void eraseRowIfLive(SupervisorDenseTable.Handle row) {
        try {
            table.eraseSwap(row);
        } catch (IllegalArgumentException ignored) {
            // Row was already retired by bridge close/finalization.
        }
    }

    private synchronized void revokeIfLive(TrustedSupervisorInbox.ChildBinding binding) {
        try {
            inbox.revoke(binding);
        } catch (IllegalArgumentException ignored) {
            // Binding may already be terminal-reported or overflow-revoked.
        }
    }

    private long nextSequence() {
        long value = sequence.incrementAndGet();
        if (value <= 0) throw new IllegalStateException("supervisor CONTROL sequence exhausted");
        return value;
    }

    private long nextChildId() {
        long value = childIds.incrementAndGet();
        if (value <= 0) throw new IllegalStateException("supervisor child identity exhausted");
        return value;
    }

    private static long stableFaultCode(Throwable failure) {
        String name = failure == null ? "unknown" : failure.getClass().getName();
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < name.length(); i++) {
            hash ^= name.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private synchronized void poison(Throwable failure) {
        if (poison == null) poison = Objects.requireNonNull(failure, "failure");
    }

    synchronized boolean healthy() {
        return !closed && !escalatedToParent && poison == null;
    }

    synchronized void requireHealthy() {
        checkHealthy();
    }

    private void checkHealthy() {
        if (closed) throw new IllegalStateException("supervisor runtime bridge closed");
        if (poison != null) {
            throw new IllegalStateException(
                    "supervisor runtime bridge is poisoned by failed CONTROL delivery",
                    poison);
        }
        if (escalatedToParent) {
            throw new IllegalStateException(
                    "supervisor runtime bridge already escalated CONTROL overflow");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (TrustedSupervisorInbox.ChildBinding binding : bindings.values()) {
            revokeIfLive(binding);
        }
        for (SupervisorDenseTable.Handle row : rowsByBinding.values()) {
            eraseRowIfLive(row);
        }
        bindings.clear();
        childIdsByBinding.clear();
        rowsByBinding.clear();
    }
}
