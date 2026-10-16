package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Host-only integration of the checked route ledger and real ActorRuntime
 * mailboxes. Graph/actor bindings and ingress are installed by the supervisor;
 * guest actors never construct or deserialize MailFrame capabilities.
 *
 * Each successful forward enqueues the ordinary typed payload, while its
 * immutable route frame travels on the VM's separate envelope metadata lane.
 * A turn without a routed envelope cannot gain routing authority merely by
 * guessing the source or a connected actor name.
 *
 * This is a trusted SHARED Java actor bridge, NOT source-level self.* lowering,
 * PRIVATE/UNTRUSTED transfer or a general-purpose guest capability.
 */
final class ActorPipelineCorrelatedMailboxRouter<M> {
    record Correlation(long generation, long flowId, long routeId, String receiver) { }

    record Admission(
            int edgeIndex, String target, long flowId, long routeId,
            boolean admitted, RuntimeException failure) {
        Admission {
            if (edgeIndex < 0) throw new IllegalArgumentException("negative edge index");
            Objects.requireNonNull(target);
            if (admitted == (failure != null))
                throw new IllegalArgumentException("admission requires exactly one outcome");
        }
    }

    private final ActorRuntime owner;
    private final ActorPipelineRoutePlan graph;
    private final ActorPipelineRouteSession session;
    private final Map<String, ActorRuntime.ActorRef<M>> targets;
    private final Map<ActorRuntime.ActorId, String> identities;
    private final Map<ActorPipelineRoutePlan.Edge, Integer> edges;

    ActorPipelineCorrelatedMailboxRouter(
            ActorRuntime runtime,
            ActorPipelineRoutePlan plan,
            Map<String, ActorRuntime.ActorRef<M>> bindings,
            long generation, int maxActiveFrames) {
        owner = Objects.requireNonNull(runtime);
        owner.requirePipelineRegistrationHostAuthority();
        graph = Objects.requireNonNull(plan);
        Objects.requireNonNull(bindings);
        Set<String> required = new HashSet<>();
        Map<ActorPipelineRoutePlan.Edge, Integer> indexed = new HashMap<>();
        for (int i = 0; i < plan.edges().size(); i++) {
            var edge = plan.edges().get(i);
            required.add(edge.source());
            required.add(edge.target());
            if (indexed.putIfAbsent(edge, i) != null)
                throw new IllegalArgumentException("duplicate graph edge");
        }
        if (!required.equals(bindings.keySet()))
            throw new IllegalArgumentException("pipeline bindings must cover exactly the graph");
        Map<String, ActorRuntime.ActorRef<M>> bound = new HashMap<>();
        Map<ActorRuntime.ActorId, String> reverse = new HashMap<>();
        for (String instance : required) {
            var ref = Objects.requireNonNull(bindings.get(instance));
            if (!owner.isAlive(ref))
                throw new IllegalArgumentException("pipeline actor must be alive in owning runtime");
            if (reverse.putIfAbsent(ref.id(), instance) != null)
                throw new IllegalArgumentException("duplicate bound actor identity");
            bound.put(instance, ref);
        }
        targets = Map.copyOf(bound);
        identities = Map.copyOf(reverse);
        edges = Map.copyOf(indexed);
        session = new ActorPipelineRouteSession(plan, generation, maxActiveFrames);
    }

    /** The ONLY entry to a new flow: an authorized supervisor-selected root. */
    void ingress(String root, M message) {
        owner.requirePipelineRegistrationHostAuthority();
        Objects.requireNonNull(message, "pipeline payload");
        // Verify the root before reserving a frame or publishing any mail.
        graph.requireIngressRoot(root);
        var frame = session.ingress(root);
        try {
            owner.sendWithPipelineFrame(targets.get(root), message, frame);
        } catch (RuntimeException | Error failed) {
            ActorPipelineRouteSession.retireDelivered(frame);
            throw failed;
        }
    }

    private ActorPipelineRouteSession.MailFrame current() {
        var frame = owner.currentPipelineRouteFrame();
        var actorId = ActorRuntime.currentActorId()
                .orElseThrow(() -> new SecurityException("missing current actor"));
        var actual = identities.get(actorId);
        if (actual == null || !actual.equals(frame.receiver()))
            throw new SecurityException("pipeline route frame receiver is not executing");
        session.requireLive(frame);
        return frame;
    }

    /** Read-only host observability; never an authority-bearing route handle. */
    Correlation currentCorrelation() {
        var frame = current();
        return new Correlation(
                frame.generation(), frame.flowId(), frame.routeId(), frame.receiver());
    }

    int outstanding() { return session.outstanding(); }

    void forward(M value) {
        var frame = current();
        sendChild(session.forward(frame), value);
    }

    void forwardRoundRobin(M value) {
        var frame = current();
        sendChild(session.forwardRoundRobin(frame), value);
    }

    void forwardTo(String target, M value) {
        var frame = current();
        sendChild(session.forwardTo(frame, target), value);
    }

    void forwardByKey(String key, M value) {
        var frame = current();
        sendChild(session.forwardByKey(frame, key), value);
    }

    /** A typed host reply travels exactly ONE edge upstream along the
     * immutable original route. At fan-in, distinct branches retain their
     * different paths and cannot accidentally reply through the other worker.
     * A full origin reply is composed of these hops; source self.reply is
     * not enabled until compiler-owned backward message handlers exist. */
    void backward(M value) {
        var frame = current();
        sendChild(session.returnOneHop(frame), value);
    }

    /** Every recipient gets an independent admission result; earlier
     * successes are not rolled back when a later mailbox is full or stopped. */
    List<Admission> forwardAll(M value) {
        var frame = current();
        Objects.requireNonNull(value, "pipeline payload");
        var children = session.forwardAll(frame);
        var result = new ArrayList<Admission>(children.size());
        var out = graph.forwardAll(frame.receiver());
        for (int i = 0; i < children.size(); i++) {
            var child = children.get(i);
            var edge = out.get(i);
            try {
                owner.sendWithPipelineFrame(targets.get(child.receiver()), value, child);
                result.add(new Admission(edges.get(edge), child.receiver(),
                        child.flowId(), child.routeId(), true, null));
            } catch (RuntimeException failure) {
                ActorPipelineRouteSession.retireDelivered(child);
                result.add(new Admission(edges.get(edge), child.receiver(),
                        child.flowId(), child.routeId(), false, failure));
            }
        }
        return List.copyOf(result);
    }

    private void sendChild(ActorPipelineRouteSession.MailFrame child, M value) {
        try {
            owner.sendWithPipelineFrame(targets.get(child.receiver()),
                    Objects.requireNonNull(value, "pipeline payload"), child);
        } catch (RuntimeException | Error failed) {
            ActorPipelineRouteSession.retireDelivered(child);
            throw failed;
        }
    }

    /** Supervisor shutdown fences new selections. Pending admitted DATA may
     * still reach recipients; no guest lowering is enabled by this method. */
    void close() {
        owner.requirePipelineRegistrationHostAuthority();
        session.close();
    }
}
