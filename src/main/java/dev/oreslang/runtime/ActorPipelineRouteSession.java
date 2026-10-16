package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Host-only routing-turn ledger. Runtime owns MailFrame instances; guest code
 * must never be permitted to create, deserialize or supply these tokens.
 * Production admission must ALSO authenticate the executing actor and graph
 * epoch and perform typed mailbox admission on the owning scheduler lane.
 *
 * This class only selects connected graph routes and preserves sealed return
 * paths; it does not enqueue messages or implement Oreslang self.* lowering.
 */
public final class ActorPipelineRouteSession {
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
        private MailFrame(ActorPipelineRouteSession session, long generation,
                          long flowId, long routeId, List<String> actorPath) {
            this.session = session;
            this.generation = generation;
            this.flowId = flowId;
            this.routeId = routeId;
            this.actorPath = List.copyOf(actorPath);
        }
        public String receiver() { return actorPath.getLast(); }
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

    public ActorPipelineRouteSession(ActorPipelineRoutePlan graph,
                                     long generation, int maxActiveFrames) {
        this.graph = Objects.requireNonNull(graph);
        if (generation < 1 || maxActiveFrames < 1 || maxActiveFrames > 65536) {
            throw new IllegalArgumentException("invalid route generation or frame budget");
        }
        this.generation = generation;
        this.capacity = maxActiveFrames;
    }

    public synchronized long generation() { return generation; }
    public synchronized int outstanding() { return active.size(); }
    public synchronized boolean closed() { return closed; }

    /** Called ONLY after supervisor authenticates the entering source actor. */
    public synchronized MailFrame ingress(String source) {
        graph.requireIngressRoot(source); // ingress must begin at a graph root
        ensureOpen();
        reserve(1);
        if (nextFlowId == Long.MAX_VALUE) throw new IllegalStateException("flow IDs exhausted");
        return newFrame(nextFlowId++, List.of(source));
    }

    public synchronized MailFrame forward(MailFrame current) {
        requireActive(current);
        return child(current, graph.forward(current.receiver()));
    }

    public synchronized MailFrame forwardRoundRobin(MailFrame current) {
        requireActive(current);
        List<ActorPipelineRoutePlan.Edge> options = graph.forwardAll(current.receiver());
        int cursor = roundRobin.getOrDefault(current.receiver(), 0);
        roundRobin.put(current.receiver(), cursor + 1 == options.size() ? 0 : cursor + 1);
        // Attempt consumes a turn even when subsequent capacity admission fails.
        return child(current, options.get(cursor));
    }

    public synchronized MailFrame forwardTo(MailFrame current, String target) {
        requireActive(current);
        return child(current, graph.forwardTo(current.receiver(), target));
    }

    public synchronized MailFrame forwardByKey(MailFrame current, String key) {
        requireActive(current);
        return child(current, graph.forwardByKey(current.receiver(), key));
    }

    /** All-or-none frame allocation, NOT transactional mailbox admission.
     * Partial mailbox writes after this method remain independently reportable. */
    public synchronized List<MailFrame> forwardAll(MailFrame current) {
        requireActive(current);
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

    /** Send to the immediate authenticated sender of the current message. */
    public synchronized ReturnPath backward(MailFrame current) {
        requireReplyable(current);
        List<String> path = current.actorPath;
        ReturnPath result = new ReturnPath(generation, current.flowId,
                current.routeId, List.of(path.getLast(), path.get(path.size() - 2)));
        active.remove(current);
        return result;
    }

    /** Return through the authenticated reverse path, not a guessed actor ID. */
    public synchronized ReturnPath reply(MailFrame current) {
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

    /** Lifecycle barrier: all previous-frame tokens become invalid. */
    public synchronized void advanceGeneration(long successor) {
        ensureOpen();
        if (successor <= generation) {
            throw new IllegalArgumentException("graph generation must increase");
        }
        active.clear();
        roundRobin.clear();
        generation = successor;
    }

    public synchronized void close() {
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
