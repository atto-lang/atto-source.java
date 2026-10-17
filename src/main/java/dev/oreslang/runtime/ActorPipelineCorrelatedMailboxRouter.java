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
final class ActorPipelineCorrelatedMailboxRouter<M>
        implements ActorRuntime.PipelineTurnAuthority {
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
                owner.sendWithPipelineFrame(targets.get(root), message, frame, this);
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

    /**
     * Single-destination routing is one-shot per authenticated inbound frame.
     * Retire the source only after mailbox publication succeeds, so a failed
     * selector/admission can be handled without silently consuming the turn,
     * while a successful first send cannot be replayed to emulate fan-out.
     */
    void forward(M value) {
        inLiveGeneration(() -> {
            var source = current();
            sendChild(session.forward(source), value);
            ActorPipelineRouteSession.retireDelivered(source);
            return null;
        });
    }

    void forwardRoundRobin(M value) {
        inLiveGeneration(() -> {
            var source = current();
            sendChild(session.forwardRoundRobin(source), value);
            ActorPipelineRouteSession.retireDelivered(source);
            return null;
        });
    }

    void forwardTo(String target, M value) {
        inLiveGeneration(() -> {
            var source = current();
            sendChild(session.forwardTo(source, target), value);
            ActorPipelineRouteSession.retireDelivered(source);
            return null;
        });
    }

    void forwardByKey(String key, M value) {
        inLiveGeneration(() -> {
            var source = current();
            sendChild(session.forwardByKey(source, key), value);
            ActorPipelineRouteSession.retireDelivered(source);
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

    /** Direct response to the authenticated flow origin. The destination is
     * derived from the sealed route path and is never caller-selected. */
    void reply(M value) {
        inLiveGeneration(() -> {
            sendChild(session.returnToOrigin(current()), value);
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
        // Pre-materialize EVERY successful AND failed branch receipt before
        // the first mailbox send. Allocation pressure must not turn a
        // nontransactional branch rejection into loss of earlier receipts.
        List<Admission> prepared;
        List<Admission> rejected;
        try {
            var all = new ArrayList<Admission>(children.size());
            var allRejected = new ArrayList<Admission>(children.size());
            for (int i = 0; i < children.size(); i++) {
                var child = children.get(i);
                int edgeIndex = edges.get(out.get(i));
                all.add(new Admission(edgeIndex, child.receiver(),
                        child.flowId(), child.routeId(), true, null));
                // Even failure receipts must be allocated BEFORE the first
                // irreversible mailbox admission. The original rejection
                // becomes this preallocated exception's cause, without
                // creating a fresh receipt after an earlier send succeeded.
                allRejected.add(new Admission(edgeIndex, child.receiver(),
                        child.flowId(), child.routeId(), false,
                        new IllegalStateException(
                                "pipeline mailbox admission rejected for " + child.receiver())));
            }
            prepared = all;
            rejected = allRejected;
        } catch (RuntimeException | Error preflight) {
            for (var unpublished : children)
                ActorPipelineRouteSession.retireDelivered(unpublished);
            throw preflight;
        }
        int next = 0;
        try {
            for (; next < children.size(); next++) {
                var child = children.get(next);
                try {
                    owner.sendWithPipelineFrame(targets.get(child.receiver()), value, child, this);
                    // No allocation: ArrayList was pre-sized and the success
                    // receipt was created before any branch publication.
                    result.add(prepared.get(next));
                } catch (RuntimeException failure) {
                    ActorPipelineRouteSession.retireDelivered(child);
                    var denied = rejected.get(next);
                    denied.failure().initCause(failure);
                    result.add(denied);
                }
            }
            // A completed fan-out attempt consumes the authenticated source
            // frame. Reusing it would permit duplicate/hidden fan-out batches.
            ActorPipelineRouteSession.retireDelivered(frame);
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
                    Objects.requireNonNull(value, "pipeline payload"), child, this);
        } catch (RuntimeException | Error failed) {
            ActorPipelineRouteSession.retireDelivered(child);
            throw failed;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void routeForward(Object value) {
        forward((M) value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void routeForwardRoundRobin(Object value) {
        forwardRoundRobin((M) value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void routeForwardTo(String target, Object value) {
        forwardTo(target, (M) value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void routeForwardByKey(String key, Object value) {
        forwardByKey(key, (M) value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void routeBackward(Object value) {
        backward((M) value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void routeReply(Object value) {
        reply((M) value);
    }

    /** Supervisor shutdown fences new selections. Pending admitted DATA may
     * still reach recipients; the compiler bridge can use this router only
     * through the matching scheduler-owned envelope authority. */
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
