package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Host-authorized, actual mailbox delivery for one immutable typed actor DAG.
 *
 * This is NOT Oreslang source self.* lowering or an untrusted actor capability.
 * The host injects a bound router into explicitly trusted actor code; every
 * operation authenticates the CURRENT executing actor from ActorRuntime's
 * carrier-independent actor context. No method accepts a caller-supplied
 * source name. ActorRuntime.send enforces the destination's mailbox, freeze,
 * ownership, runtime affiliation, isolate and STOP checks for every edge.
 *
 * One fan-out is intentionally non-transactional. Each recipient has an
 * independent admission receipt. Admission never means processing / DONE.
 */
public final class ActorPipelineMailboxRouter<M> {
    public record Admission(String target, boolean admitted, RuntimeException failure) {
        public Admission {
            Objects.requireNonNull(target, "target");
            if (admitted == (failure != null)) {
                throw new IllegalArgumentException(
                        "admission must be either accepted or have one failure");
            }
        }
    }

    private final ActorRuntime owner;
    private final ActorPipelineRoutePlan graph;
    private final Map<ActorRuntime.ActorId, String> identities;
    private final Map<String, ActorRuntime.ActorRef<M>> targets;
    // Each bound graph generation owns an independent round-robin cursor.
    private final Map<String, Integer> roundRobinCursors = new HashMap<>();
    private final Object selectionLock = new Object();
    // Admissions hold the read lease through ActorRuntime.send. Retirement
    // obtains the exclusive lease and forbids any post-close publication.
    private final ReentrantReadWriteLock retirementGate =
            new ReentrantReadWriteLock(true);
    private boolean retired;

    /**
     * Must run on the trusted host/supervisor, never from actor code,
     * ActorGroup CONTROL Mailman, or a scheduler continuation.
     *
     * Nominal payload typing is checked by the graph; this Java host adapter
     * additionally requires a single compile-time M for every actor ref.
     * Compiler lowering must later prove the actual Oreslang ActorMail<T>
     * concrete nominal type and ownership at each route call.
     */
    public ActorPipelineMailboxRouter(
            ActorRuntime runtime, ActorPipelineRoutePlan plan,
            Map<String, ActorRuntime.ActorRef<M>> actorBindings) {
        this.owner = Objects.requireNonNull(runtime, "runtime");
        // This includes the force-cancellation callback reentry fence.
        this.owner.requirePipelineRegistrationHostAuthority();
        this.graph = Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(actorBindings, "actorBindings");

        // Reject incomplete or superfluous connections before exposing any
        // mutable routing entrypoint. A sink/transform may be the final edge
        // target yet must still be bound to an actual ActorRef.
        Set<String> required = new HashSet<>();
        for (ActorPipelineRoutePlan.Edge edge : plan.edges()) {
            required.add(edge.source());
            required.add(edge.target());
        }
        if (!required.equals(actorBindings.keySet())) {
            throw new IllegalArgumentException(
                    "pipeline bindings must cover exactly every graph instance");
        }

        Map<String, ActorRuntime.ActorRef<M>> bound = new HashMap<>();
        Map<ActorRuntime.ActorId, String> reverse = new HashMap<>();
        for (String instance : required) {
            ActorRuntime.ActorRef<M> ref = Objects.requireNonNull(
                    actorBindings.get(instance), "pipeline actor ref");
            if (!runtime.isAlive(ref)) {
                throw new IllegalArgumentException(
                        "pipeline actor is not an active ref in owning runtime");
            }
            if (reverse.putIfAbsent(ref.id(), instance) != null) {
                throw new IllegalArgumentException(
                        "pipeline graph cannot bind two instances to one actor ref");
            }
            bound.put(instance, ref);
        }
        this.targets = Map.copyOf(bound);
        this.identities = Map.copyOf(reverse);
    }

    /** Supervisor-only generation retirement; does not stop actors or
     * cancel already admitted messages. */
    public void close() {
        owner.requirePipelineRegistrationHostAuthority();
        var write = retirementGate.writeLock();
        write.lock();
        try {
            retired = true;
        } finally {
            write.unlock();
        }
    }

    private <R> R inLiveRoutingGeneration(Supplier<R> operation) {
        var read = retirementGate.readLock();
        read.lock();
        try {
            if (retired) {
                throw new IllegalStateException("pipeline routing generation is retired");
            }
            return operation.get();
        } finally {
            read.unlock();
        }
    }

    /** The caller is the actual executing actor, never a guessed name. */
    private String currentSource() {
        // Actor identity alone also exists during startup/lifecycle callbacks.
        // Admit a route only under an actual scheduler-owned DATA receive.
        owner.requireCurrentPipelineReceiveTurn();
        if (ActorRuntime.currentActorRuntime() != owner
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new SecurityException(
                    "pipeline routing requires an actor turn in its owning runtime");
        }
        if (ActorRuntime.currentActorKind() != ActorRuntime.ActorKind.SHARED) {
            // A PRIVATE actor owns an isolated heap: passing it this mutable
            // host selector would leak a cross-isolate object capability.
            // UNTRUSTED routing is equally unsupported. Both need sealed,
            // scheduler-owned route handles, not this trusted-host adapter.
            // Reject before changing the RR cursor or admitting any edge.
            throw new SecurityException(
                    "host pipeline mailbox routing requires a trusted SHARED actor turn");
        }
        ActorRuntime.ActorId actorId = ActorRuntime.currentActorId()
                .orElseThrow(() -> new SecurityException(
                        "pipeline routing requires an executing actor turn"));
        String source = identities.get(actorId);
        if (source == null) {
            throw new SecurityException(
                    "executing actor is not a connected pipeline instance");
        }
        if (!targets.get(source).isAlive()) {
            throw new IllegalStateException(
                    "pipeline sender is stopped or no longer active");
        }
        return source;
    }

    private void admit(ActorPipelineRoutePlan.Edge selected, M value) {
        Objects.requireNonNull(value, "pipeline payload; use Option<T> for null");
        // Per-destination freeze/isolation, bounded mailbox reservation,
        // actor stop and runtime affiliation are owned by ActorRuntime.send.
        owner.send(targets.get(selected.target()), value);
    }

    /** One downstream only. Branching requires an explicit routing policy. */
    public void forward(M value) {
        inLiveRoutingGeneration(() -> {
            admit(graph.forward(currentSource()), value);
            return null;
        });
    }

    /** Independent real mailbox admission with explicit partial results. */
    public List<Admission> forwardAll(M value) {
        return inLiveRoutingGeneration(() -> {
            List<ActorPipelineRoutePlan.Edge> edges = graph.forwardAll(currentSource());
            Objects.requireNonNull(value, "pipeline payload; use Option<T> for null");
            ArrayList<Admission> receipts = new ArrayList<>(edges.size());
            for (ActorPipelineRoutePlan.Edge edge : edges) {
                try {
                    admit(edge, value);
                    receipts.add(new Admission(edge.target(), true, null));
                } catch (RuntimeException admissionFailure) {
                    receipts.add(new Admission(edge.target(), false, admissionFailure));
                }
            }
            return List.copyOf(receipts);
        });
    }

    /** Every selection counts, even if the subsequent mailbox rejects it. */
    public void forwardRoundRobin(M value) {
        inLiveRoutingGeneration(() -> {
            String source = currentSource();
            List<ActorPipelineRoutePlan.Edge> connected = graph.forwardAll(source);
            ActorPipelineRoutePlan.Edge selected;
            synchronized (selectionLock) {
                int cursor = roundRobinCursors.getOrDefault(source, 0);
                selected = connected.get(cursor);
                roundRobinCursors.put(source,
                        cursor + 1 == connected.size() ? 0 : cursor + 1);
            }
            admit(selected, value);
            return null;
        });
    }

    public void forwardTo(String target, M value) {
        inLiveRoutingGeneration(() -> {
            admit(graph.forwardTo(currentSource(), target), value);
            return null;
        });
    }

    public void forwardByKey(String key, M value) {
        inLiveRoutingGeneration(() -> {
            admit(graph.forwardByKey(currentSource(), key), value);
            return null;
        });
    }
}
