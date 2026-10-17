package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Host-only routing-turn ledger. Runtime owns MailFrame instances; guest code
 * must never be permitted to create, deserialize or supply these tokens.
 * Production admission must ALSO authenticate the executing actor and graph
 * epoch and perform typed mailbox admission on the owning scheduler lane.
 *
 * This class only selects connected graph routes and preserves sealed return
 * paths; it does not enqueue messages or implement Oreslang self.* lowering.
 */
final class ActorPipelineRouteSession {
    /** Reverse route begins with the replying actor and ends at the parent or
     * authenticated original source. Not a bearer ActorRef or send authority. */
    public static final class ReturnPath {
        private final long generation;
        private final long flowId;
        private final long routeId;
        private final List<String> reverseActors;

        // A routing result is a sealed VM-created witness, never an
        // arbitrary caller-constructed bearer reply path.
        private ReturnPath(long generation, long flowId, long routeId,
                           List<String> reverseActors) {
            this.reverseActors = List.copyOf(reverseActors);
            if (this.reverseActors.size() < 2) {
                throw new IllegalArgumentException("reply requires a parent actor");
            }
            this.generation = generation;
            this.flowId = flowId;
            this.routeId = routeId;
        }

        public long generation() { return generation; }
        public long flowId() { return flowId; }
        public long routeId() { return routeId; }
        public List<String> reverseActors() { return reverseActors; }
        public String nextHop() { return reverseActors.get(1); }
        public String origin() { return reverseActors.getLast(); }
    }

    /** Identity-only host handle; no public constructor or deserializer. */
    public static final class MailFrame {
        private final ActorPipelineRouteSession session;
        private final long generation;
        private final long flowId;
        private final long routeId;
        private final List<String> actorPath;
        private final int receiverIndex;
        private final boolean returning;
        private MailFrame(ActorPipelineRouteSession session, long generation,
                          long flowId, long routeId, List<String> actorPath) {
            this(session, generation, flowId, routeId, actorPath,
                    actorPath.size() - 1, false);
        }
        private MailFrame(ActorPipelineRouteSession session, long generation,
                          long flowId, long routeId, List<String> actorPath,
                          int receiverIndex, boolean returning) {
            this.session = session;
            this.generation = generation;
            this.flowId = flowId;
            this.routeId = routeId;
            this.actorPath = List.copyOf(actorPath);
            if (receiverIndex < 0 || receiverIndex >= this.actorPath.size())
                throw new IllegalArgumentException("route receiver index out of bounds");
            this.receiverIndex = receiverIndex;
            this.returning = returning;
        }
        public String receiver() { return actorPath.get(receiverIndex); }
        public long generation() { return generation; }
        public long flowId() { return flowId; }
        public long routeId() { return routeId; }
    }

    private final ActorPipelineRoutePlan graph;
    private final int capacity;
    private final IdentityHashMap<MailFrame, Boolean> active = new IdentityHashMap<>();
    // Cursor is scoped to this trusted graph session / generation.
    private final Map<String, Integer> roundRobin = new HashMap<>();
    private long generation;
    private long nextFlowId = 1;
    private long nextRouteId = 1;
    private boolean closed;
    // VM-owned nonblocking mailbox publication scope, not a guest state loan.
    private boolean publicationInProgress;

    ActorPipelineRouteSession(ActorPipelineRoutePlan graph,
                                     long generation, int maxActiveFrames) {
        this.graph = Objects.requireNonNull(graph);
        if (generation < 1 || maxActiveFrames < 1 || maxActiveFrames > 65536) {
            throw new IllegalArgumentException("invalid route generation or frame budget");
        }
        this.generation = generation;
        this.capacity = maxActiveFrames;
        // Seed every source+generation RR cursor before actor routing begins.
        // Updating an existing HashMap entry during a turn must not allocate a
        // first-use selector node; this matches the other native/host backends.
        for (ActorPipelineRoutePlan.Edge edge : graph.edges()) {
            roundRobin.putIfAbsent(edge.source(), 0);
        }
    }

    public synchronized long generation() { return generation; }
    public synchronized int outstanding() { return active.size(); }
    public synchronized boolean closed() { return closed; }

    /** VM-only validation for host-reported immutable correlation metadata. */
    synchronized void requireLive(MailFrame frame) {
        requireActive(frame);
    }

    /** Called ONLY after supervisor authenticates the entering source actor. */
    synchronized MailFrame ingress(String source) {
        graph.requireIngressRoot(source); // ingress must begin at a graph root
        ensureOpen();
        reserve(1);
        if (nextFlowId == Long.MAX_VALUE) throw new IllegalStateException("flow IDs exhausted");
        MailFrame ingress = newFrame(nextFlowId, List.of(source));
        nextFlowId++;
        return ingress;
    }

    public synchronized MailFrame forward(MailFrame current) {
        requireForward(current);
        return child(current, graph.forward(current.receiver()));
    }

    public synchronized MailFrame forwardRoundRobin(MailFrame current) {
        requireForward(current);
        List<ActorPipelineRoutePlan.Edge> options = graph.forwardAll(current.receiver());
        Integer cursor = roundRobin.get(current.receiver());
        if (cursor == null) throw new IllegalStateException("round-robin cursor missing");
        roundRobin.put(current.receiver(), cursor + 1 == options.size() ? 0 : cursor + 1);
        // Attempt consumes a turn even when subsequent capacity admission fails.
        return child(current, options.get(cursor));
    }

    public synchronized MailFrame forwardTo(MailFrame current, String target) {
        requireForward(current);
        return child(current, graph.forwardTo(current.receiver(), target));
    }

    public synchronized MailFrame forwardByKey(MailFrame current, String key) {
        requireForward(current);
        return child(current, graph.forwardByKey(current.receiver(), key));
    }

    /** All-or-none frame allocation, NOT transactional mailbox admission.
     * Partial mailbox writes after this method remain independently reportable. */
    public synchronized List<MailFrame> forwardAll(MailFrame current) {
        requireForward(current);
        List<ActorPipelineRoutePlan.Edge> edges = graph.forwardAll(current.receiver());
        reserve(edges.size());
        reserveRouteIds(edges.size());
        // Prepare every identity and immutable path before publication. A
        // failed allocation must never leave an invisible live branch in the
        // host ledger (mailbox admission remains separately non-atomic).
        List<MailFrame> prepared = new ArrayList<>(edges.size());
        long routeId = nextRouteId;
        for (ActorPipelineRoutePlan.Edge edge : edges) {
            prepared.add(new MailFrame(this, generation, current.flowId,
                    routeId++, extend(current.actorPath, edge.target())));
        }
        List<MailFrame> result = List.copyOf(prepared);
        try {
            for (MailFrame child : result) {
                active.put(child, Boolean.TRUE);
            }
        } catch (RuntimeException | Error failure) {
            for (MailFrame child : result) active.remove(child);
            throw failure;
        }
        nextRouteId = routeId;
        return result;
    }

    /** VM-only actual reverse DATA hop. The original trusted path is carried
     * intact across the return, with a decreasing receiver index. The branch
     * keeps its flow ID while each hop receives a distinct one-shot route ID.
     * The previous frame is consumed only when its child frame is published.
     * This is NOT an actor-controlled named destination or reverse broadcast. */
    synchronized MailFrame returnOneHop(MailFrame current) {
        requireActive(current);
        if (current.receiverIndex == 0)
            throw new IllegalStateException("pipeline origin has no upstream return hop");
        reserveRouteIds(1);
        MailFrame child = new MailFrame(this, generation, current.flowId,
                nextRouteId, current.actorPath, current.receiverIndex - 1, true);
        active.put(child, Boolean.TRUE);
        active.remove(current);
        nextRouteId++;
        return child;
    }

    /** Send to the immediate authenticated sender of the current message. */
    public synchronized ReturnPath backward(MailFrame current) {
        requireForward(current);
        requireReplyable(current);
        List<String> path = current.actorPath;
        ReturnPath result = new ReturnPath(generation, current.flowId,
                current.routeId, List.of(path.getLast(), path.get(path.size() - 2)));
        active.remove(current);
        return result;
    }

    /** Return through the authenticated reverse path, not a guessed actor ID. */
    public synchronized ReturnPath reply(MailFrame current) {
        requireForward(current);
        requireReplyable(current);
        List<String> reversed = new ArrayList<>(current.actorPath);
        java.util.Collections.reverse(reversed);
        ReturnPath result = new ReturnPath(generation, current.flowId,
                current.routeId, reversed);
        active.remove(current);
        return result;
    }

    /** Complete a one-way message without a reply; never replay a terminal. */
    public synchronized void complete(MailFrame current) {
        requireActive(current);
        active.remove(current);
    }

    /**
     * VM-owned mailbox cleanup. Retire a delivered or cancelled envelope
     * without granting a caller the right to complete arbitrary frames.
     * Terminal selection may already have consumed the frame. A previous
     * generation must never retire a successor's frame.
     */
    static void retireDelivered(MailFrame frame) {
        if (frame == null) return;
        frame.session.retireIfActive(frame);
    }

    private synchronized void retireIfActive(MailFrame frame) {
        if (!closed && frame.session == this && frame.generation == generation) {
            active.remove(frame);
        }
    }

    /** Publish a bounded batch without permitting a supervisor generation
     * fence to overtake it. The trusted callback must not await, block on
     * mailbox credits, or schedule synchronous guest execution.
     * Reentrant graph lifecycle changes are explicitly rejected. */
    synchronized <T> T withAdmissionBarrier(MailFrame source, Supplier<T> action) {
        Objects.requireNonNull(action);
        requireActive(source);
        if (publicationInProgress) {
            throw new IllegalStateException("nested pipeline mailbox publication");
        }
        publicationInProgress = true;
        try {
            return action.get();
        } finally {
            publicationInProgress = false;
        }
    }

    /** A clean topology change is only allowed after every current message
     * has completed. In-flight actor messages must never silently disappear. */
    synchronized void advanceGeneration(long successor) {
        rejectPublicationFence();
        validateSuccessor(successor);
        if (!active.isEmpty()) {
            throw new IllegalStateException(
                    "pipeline reconfiguration requires a quiescent message barrier");
        }
        roundRobin.replaceAll((source, cursor) -> 0);
        generation = successor;
    }

    /** Explicit supervisor cancellation barrier. Previous frames become stale,
     * and any actual mailbox writers must be failed/drained by the scheduler
     * before a replacement graph is published. This ledger is host-only. */
    synchronized int abortAndAdvanceGeneration(long successor) {
        rejectPublicationFence();
        validateSuccessor(successor);
        int cancelled = active.size();
        active.clear();
        roundRobin.replaceAll((source, cursor) -> 0);
        generation = successor;
        return cancelled;
    }

    private void rejectPublicationFence() {
        if (publicationInProgress) {
            throw new IllegalStateException(
                    "cannot fence pipeline generation during mailbox publication");
        }
    }

    private void validateSuccessor(long successor) {
        ensureOpen();
        if (successor <= generation) {
            throw new IllegalArgumentException("graph generation must increase");
        }
    }

    synchronized void close() {
        rejectPublicationFence();
        closed = true;
        active.clear();
    }

    private void requireActive(MailFrame current) {
        Objects.requireNonNull(current);
        ensureOpen();
        if (current.session != this || current.generation != generation
                || !active.containsKey(current)) {
            throw new IllegalStateException("stale, foreign or completed actor route frame");
        }
    }

    private void requireForward(MailFrame current) {
        requireActive(current);
        if (current.returning)
            throw new IllegalStateException(
                    "reverse DATA can only travel on its authenticated return path");
    }

    private void requireReplyable(MailFrame current) {
        requireActive(current);
        if (current.actorPath.size() < 2) {
            throw new IllegalStateException("origin has no upstream reply path");
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("actor graph is closed");
    }

    private void reserve(int amount) {
        if (amount > capacity - active.size()) {
            throw new IllegalStateException("actor route frame budget exhausted");
        }
    }

    private void reserveRouteIds(int amount) {
        if (nextRouteId > Long.MAX_VALUE - amount) {
            throw new IllegalStateException("route IDs exhausted");
        }
    }

    private MailFrame newFrame(long flowId, List<String> path) {
        reserveRouteIds(1);
        MailFrame frame = new MailFrame(this, generation, flowId,
                nextRouteId++, path);
        active.put(frame, Boolean.TRUE);
        return frame;
    }

    private MailFrame child(MailFrame current, ActorPipelineRoutePlan.Edge edge) {
        reserve(1);
        if (current.actorPath.size() >= 1024) {
            throw new IllegalStateException("actor route depth exceeded");
        }
        return newFrame(current.flowId, extend(current.actorPath, edge.target()));
    }

    private static List<String> extend(List<String> path, String next) {
        if (path.size() >= 1024) {
            throw new IllegalStateException("actor route depth exceeded");
        }
        List<String> longer = new ArrayList<>(path.size() + 1);
        longer.addAll(path);
        longer.add(next);
        return longer;
    }
}
