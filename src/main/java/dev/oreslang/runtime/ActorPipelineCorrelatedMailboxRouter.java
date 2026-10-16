package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

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
    // Host retirement has exclusive publication authority. A guest actor
    // must never PARK its carrier waiting for this host-controlled gate.
    private final ReentrantReadWriteLock publicationGate =
            new ReentrantReadWriteLock(true);

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
            owner.requireSharedPipelineBinding(ref);
            if (reverse.putIfAbsent(ref.id(), instance) != null)
                throw new IllegalArgumentException("duplicate bound actor identity");
            bound.put(instance, ref);
        }
        targets = Map.copyOf(bound);
        identities = Map.copyOf(reverse);
        edges = Map.copyOf(indexed);
        session = new ActorPipelineRouteSession(plan, generation, maxActiveFrames);
    }

    /**
     * A selection and its actual mailbox publication share a nonblocking
     * read lease. If CLOSE is waiting, do not park an actor's physical carrier
     * and do not publish a DATA envelope after the terminal barrier.
     */
    private <R> R inLiveGeneration(Supplier<R> action) {
        var read = publicationGate.readLock();
        if (publicationGate.hasQueuedThreads() || !read.tryLock()) {
            throw new IllegalStateException("pipeline retirement is pending");
        }
        try {
            if (session.closed()) {
                throw new IllegalStateException("pipeline generation is retired");
            }
            return action.get();
        } finally {
            read.unlock();
        }
    }

    /** The ONLY entry to a new flow: an authorized supervisor-selected root. */
    void ingress(String root, M message) {
        owner.requirePipelineRegistrationHostAuthority();
        inLiveGeneration(() -> {
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
            return null;
        });
    }

    private ActorPipelineRouteSession.MailFrame current() {
        var frame = owner.currentPipelineRouteFrame();
        if (frame == null) {
            throw new SecurityException(
                    "pipeline operation requires a routed ActorMail envelope");
        }
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
        inLiveGeneration(() -> {
            sendChild(session.forward(current()), value);
            return null;
        });
    }

    void forwardRoundRobin(M value) {
        inLiveGeneration(() -> {
            sendChild(session.forwardRoundRobin(current()), value);
            return null;
        });
    }

    void forwardTo(String target, M value) {
        inLiveGeneration(() -> {
            sendChild(session.forwardTo(current(), target), value);
            return null;
        });
    }

    void forwardByKey(String key, M value) {
        inLiveGeneration(() -> {
            sendChild(session.forwardByKey(current(), key), value);
            return null;
        });
    }

    /** A typed host reply travels exactly ONE edge upstream along the
     * immutable original route. At fan-in, distinct branches retain their
     * different paths and cannot accidentally reply through the other worker.
     * A full origin reply is composed of these hops; source self.reply is
     * not enabled until compiler-owned backward message handlers exist. */
    void backward(M value) {
        inLiveGeneration(() -> {
            sendChild(session.returnOneHop(current()), value);
            return null;
        });
    }

    /** Every recipient gets an independent admission result; earlier
     * successes are not rolled back when a later mailbox is full or stopped. */
    List<Admission> forwardAll(M value) {
        return inLiveGeneration(() -> {
        var frame = current();
        Objects.requireNonNull(value, "pipeline payload");
        // Allocate independent receipt slots and resolve the immutable edge
        // order BEFORE reserving any route frame. A failed host allocation
        // must never strand a live, undeliverable correlation child.
        var out = graph.forwardAll(frame.receiver());
        var result = new ArrayList<Admission>(out.size());
        // Allocate the read-only wrapper before any live correlation frame
        // or mailbox DATA publication. No final List.copyOf allocation
        // occurs after successful sends have become externally visible.
        var completed = Collections.unmodifiableList(result);
        var children = session.forwardAll(frame);
        int next = 0;
        try {
            for (; next < children.size(); next++) {
                var child = children.get(next);
                var edge = out.get(next);
                // Construct the accepted receipt BEFORE DATA publication:
                // no metadata allocation may turn a successful mailbox write
                // into an apparently failed admission.
                var accepted = new Admission(edges.get(edge), child.receiver(),
                        child.flowId(), child.routeId(), true, null);
                try {
                    owner.sendWithPipelineFrame(targets.get(child.receiver()), value, child);
                    result.add(accepted);
                } catch (RuntimeException failure) {
                    ActorPipelineRouteSession.retireDelivered(child);
                    result.add(new Admission(edges.get(edge), child.receiver(),
                            child.flowId(), child.routeId(), false, failure));
                }
            }
            return completed;
        } catch (RuntimeException | Error failure) {
            // Previously published branches retain their independent frames.
            // The current and later frames have not been published if the
            // error happened during receipt construction. A fatal error
            // during send is treated as terminal runtime failure; session
            // cleanup is idempotent and never claims a delivery succeeded.
            for (int rest = next; rest < children.size(); rest++)
                ActorPipelineRouteSession.retireDelivered(children.get(rest));
            throw failure;
        }
        });
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
        var write = publicationGate.writeLock();
        write.lock();
        try {
            session.close();
        } finally {
            write.unlock();
        }
    }
}
