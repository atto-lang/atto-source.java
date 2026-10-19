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

    private final TrustedSupervisorInbox inbox;
    private final ParentEscalation escalation;
    private final Map<ActorRuntime.ActorRef<?>, TrustedSupervisorInbox.ChildBinding> bindings =
            new IdentityHashMap<>();
    private final Map<TrustedSupervisorInbox.ChildBinding, Long> childIdsByBinding =
            new IdentityHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong childIds = new AtomicLong();
    private boolean closed;

    SupervisorRuntimeBridge(
            TrustedSupervisorInbox inbox,
            ParentEscalation escalation) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.escalation = Objects.requireNonNull(escalation, "escalation");
    }

    /**
     * Register a verified local child capability and attach a runtime-owned
     * finalization callback. ActorRef generation is used as the incarnation fence.
     */
    synchronized void register(ActorRuntime.ActorRef<?> child) {
        checkOpen();
        Objects.requireNonNull(child, "child");
        if (bindings.containsKey(child)) {
            throw new IllegalArgumentException("child already registered");
        }
        long childId = nextChildId();
        TrustedSupervisorInbox.ChildBinding binding =
                inbox.bindVerifiedChild(childId, child.generation());
        bindings.put(child, binding);
        childIdsByBinding.put(binding, childId);

        child.getDone().whenCompleteRuntime((ignored, doneFailure) ->
                onFinalized(child, binding, doneFailure));
    }

    private void onFinalized(
            ActorRuntime.ActorRef<?> child,
            TrustedSupervisorInbox.ChildBinding binding,
            Throwable doneFailure) {
        final Throwable failure = child.failure().orElse(doneFailure);
        synchronized (this) {
            bindings.remove(child, binding);
            if (closed || failure == null) {
                revokeIfLive(binding);
                return;
            }
        }

        long seq = nextSequence();
        long faultCode = stableFaultCode(failure);
        TrustedSupervisorInbox.Admission result =
                inbox.offerTrustedPanic(binding, seq, faultCode);
        if (result == TrustedSupervisorInbox.Admission.ESCALATE_TO_PARENT) {
            TrustedSupervisorInbox.FailureNotice notice =
                    new TrustedSupervisorInbox.FailureNotice(
                            childIdFor(binding), child.generation(), seq, faultCode);
            if (!escalation.escalate(notice)) {
                throw new IllegalStateException(
                        "supervisor CONTROL overflow escalation was not durably accepted");
            }
        } else if (result == TrustedSupervisorInbox.Admission.REJECTED) {
            throw new IllegalStateException(
                    "runtime-authenticated finalized child failure was rejected");
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

    private synchronized long childIdFor(TrustedSupervisorInbox.ChildBinding binding) {
        Long value = childIdsByBinding.get(binding);
        if (value == null) throw new IllegalStateException("trusted child identity unavailable");
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

    private void checkOpen() {
        if (closed) throw new IllegalStateException("supervisor runtime bridge closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (TrustedSupervisorInbox.ChildBinding binding : bindings.values()) {
            revokeIfLive(binding);
        }
        bindings.clear();
        childIdsByBinding.clear();
    }
}
