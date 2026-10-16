package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        if (ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()
                || OresScheduler.current() != null) {
            throw new SecurityException(
                    "pipeline graph bindings are supervisor-only");
        }
        this.owner = Objects.requireNonNull(runtime, "runtime");
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

    /** The caller is the actual executing actor, never a guessed name. */
    private String currentSource() {
        if (ActorRuntime.currentActorRuntime() != owner
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new SecurityException(
                    "pipeline routing requires an actor turn in its owning runtime");
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
        admit(graph.forward(currentSource()), value);
    }

    /** One real admitted mailbox message for each successful destination.
     * Failures remain visible even when earlier destinations succeeded.
     * Do not await or execute actor code inside this fan-out admission loop. */
    public List<Admission> forwardAll(M value) {
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
    }

    /** Attempt always consumes one turn of the source-local cursor. */
    public void forwardRoundRobin(M value) {
        admit(graph.forwardRoundRobin(currentSource()), value);
    }

    /** Target must be a declared outbound edge of the executing actor. */
    public void forwardTo(String target, M value) {
        admit(graph.forwardTo(currentSource(), target), value);
    }

    /** Stable, strict UTF-8 routing under this immutable graph generation. */
    public void forwardByKey(String key, M value) {
        admit(graph.forwardByKey(currentSource(), key), value);
    }
}
