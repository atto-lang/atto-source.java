package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * EXPERIMENTAL trusted-host-bound actor-to-egress ingress, not a guest language
 * import or a general NETWORK grant. Each instance binds one real untrusted
 * ActorRef to a host-selected literal 127.0.0.1 endpoint and host-owned
 * child/supervisor/tenant quotas. No guest-supplied actor ID, URL, scope,
 * clock, transport handle, callback or executor is accepted.
 *
 * Host completions update only private atomic state. Never complete a
 * guest-controlled CompletableFuture from a trusted host executor: its
 * synchronous callbacks would run with host authority. Guest turns receive
 * only bounded numeric tickets and immutable polled results.
 *
 * Host-side Java actor test is distinct from a Graal protected-guest test.
 */
final class TrustedActorLoopbackEgressBridge implements AutoCloseable {
    enum State { PENDING, OK, FAILED, CANCELLED }
    record Poll(State state, int httpStatus) {
        Poll { Objects.requireNonNull(state); }
    }

    private static final int MAX_TICKETS = 16;
    private final ActorRuntime runtime;
    private final ActorRuntime.ActorRef<?> actor;
    private final TrustedHttpLoopbackProbe broker;
    private final Executor hostDispatcher;
    private final ConcurrentHashMap<Long, AtomicReference<Poll>> tickets =
            new ConcurrentHashMap<>();
    private final AtomicLong nextTicket = new AtomicLong();
    private boolean closed;

    TrustedActorLoopbackEgressBridge(
            ActorRuntime runtime,
            ActorRuntime.ActorRef<?> actor,
            TrustedHierarchicalNetworkGate gate,
            TrustedHierarchicalNetworkGate.Scope child,
            TrustedHierarchicalNetworkGate.Scope supervisor,
            TrustedHierarchicalNetworkGate.Scope tenant,
            int fixedLoopbackPort,
            Executor hostDispatcher) {
        requireHost();
        this.runtime = Objects.requireNonNull(runtime);
        this.actor = Objects.requireNonNull(actor);
        if (actor.kind() != ActorRuntime.ActorKind.UNTRUSTED || !actor.isAlive()) {
            throw new IllegalArgumentException("only a live untrusted actor may bind");
        }
        this.hostDispatcher = Objects.requireNonNull(hostDispatcher);
        this.broker = new TrustedHttpLoopbackProbe(
                gate, child, supervisor, tenant, fixedLoopbackPort);
    }

    /**
     * Called only through the explicitly selected actor intrinsic/host seam.
     * No user-controlled identity or arbitrary URL is accepted.
     * Admission is bounded before enqueuing on a trusted host executor.
     */
    synchronized long submitGetFromCurrentActor() {
        requireBoundActorTurn();
        if (closed) throw new IllegalStateException("egress binding closed");
        if (!actor.isAlive()) throw new SecurityException("actor terminated");
        if (tickets.size() >= MAX_TICKETS) {
            throw new IllegalStateException("too many outstanding egress tickets");
        }
        long id = nextTicket.incrementAndGet();
        if (id <= 0) throw new IllegalStateException("ticket sequence exhausted");
        var slot = new AtomicReference<>(new Poll(State.PENDING, 0));
        tickets.put(id, slot);
        try {
            hostDispatcher.execute(() -> dispatchHost(slot));
        } catch (RuntimeException rejected) {
            tickets.remove(id, slot);
            throw new IllegalStateException("host dispatcher rejected egress request", rejected);
        }
        return id;
    }

    /**
     * Single-consumer poll; consumes terminal state so completed tickets
     * cannot build up indefinitely or be replayed.
     */
    Poll pollFromCurrentActor(long id) {
        requireBoundActorTurn();
        AtomicReference<Poll> slot = tickets.get(id);
        if (slot == null) throw new SecurityException("invalid egress ticket");
        Poll result = slot.get();
        if (result.state() != State.PENDING && !tickets.remove(id, slot)) {
            throw new SecurityException("egress ticket has already been consumed");
        }
        return result;
    }

    private void dispatchHost(AtomicReference<Poll> slot) {
        // An inline executor has inherited the untrusted actor turn:
        // fail closed rather than granting host authority.
        if (ActorRuntime.inActorExecution() ||
                ActorRuntime.inActorGroupMailmanExecution()) {
            slot.set(new Poll(State.FAILED, 0));
            return;
        }
        synchronized (this) {
            if (closed || !actor.isAlive()) {
                slot.set(new Poll(State.CANCELLED, 0));
                return;
            }
            try {
                // Deliberately no guest-supplied address, timestamp or policy.
                // Host HTTP completion updates only private atomic state.
                broker.getStatus().whenComplete((status, failure) -> {
                    slot.set(failure == null
                            ? new Poll(State.OK, status)
                            : new Poll(State.FAILED, 0));
                });
            } catch (RuntimeException failure) {
                slot.set(new Poll(State.FAILED, 0));
            }
        }
    }

    private void requireBoundActorTurn() {
        if (ActorRuntime.currentActorKind() != ActorRuntime.ActorKind.UNTRUSTED
                || ActorRuntime.currentActorRuntime() != runtime
                || !ActorRuntime.currentActorId().equals(java.util.Optional.of(actor.id()))
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new SecurityException("not the authenticated bound untrusted actor turn");
        }
    }

    static void requireHost() {
        if (ActorRuntime.inActorExecution() ||
                ActorRuntime.inActorGroupMailmanExecution()) {
            throw new SecurityException("network egress bindings belong to trusted host");
        }
    }

    @Override public synchronized void close() {
        requireHost();
        closed = true;
        // Repeated close retries outstanding host cancellation.
        broker.close();
    }
}
