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
        // Exactly one original forward/return frame was consumed to create
        // this reverse hop. This is an identity relation, not a flow/path
        // equality guess. Never serialized into an ActorMail payload.
        private final MailFrame returnParent;
        // Guarded by the owning session monitor.
        private boolean rollbackConsumed;
        // Set at the exact mailbox enqueue linearization point. Unlike
        // active-frame membership, this flag is monotonic and survives
        // normal delivery/STOP frame retirement. Published DATA can NEVER
        // be restored to its source even after a later runtime failure.
        // 0=free, 1=reserved for one send, 2=irrevocably enqueued.
        // CAS prevents two concurrent host publishers from enqueueing the
        // same sealed frame; only the transition to 2 is irreversible.
        private final java.util.concurrent.atomic.AtomicInteger publicationState =
                new java.util.concurrent.atomic.AtomicInteger();
        private MailFrame(ActorPipelineRouteSession session, long generation,
                          long flowId, long routeId, List<String> actorPath) {
            this(session, generation, flowId, routeId, actorPath,
                    actorPath.size() - 1, false, null);
        }
        private MailFrame(ActorPipelineRouteSession session, long generation,
                          long flowId, long routeId, List<String> actorPath,
                          int receiverIndex, boolean returning) {
            this(session, generation, flowId, routeId, actorPath,
                    receiverIndex, returning, null);
        }
        private MailFrame(ActorPipelineRouteSession session, long generation,
                          long flowId, long routeId, List<String> actorPath,
                          int receiverIndex, boolean returning,
                          MailFrame returnParent) {
            this.session = session;
            this.generation = generation;
            this.flowId = flowId;
            this.routeId = routeId;
            this.actorPath = List.copyOf(actorPath);
            if (receiverIndex < 0 || receiverIndex >= this.actorPath.size())
                throw new IllegalArgumentException("route receiver index out of bounds");
            this.receiverIndex = receiverIndex;
            this.returning = returning;
            this.returnParent = returnParent;
        }
        public String receiver() { return actorPath.get(receiverIndex); }
        public long generation() { return generation; }
        public long flowId() { return flowId; }
        public long routeId() { return routeId; }
    }

    private final ActorPipelineRoutePlan graph;
    private final int capacity;
    // A selector-only session must not be used to attach VM route metadata
    // to actual mailbox envelopes. Only supervisor-bound actor IDs confer
    // recipient identity; graph instance strings are not capabilities.
    private final Map<String, ?> runtimeRecipients;
    private final IdentityHashMap<MailFrame, Boolean> active = new IdentityHashMap<>();
    // A trusted close/generation fence must not retire or reuse a frame
    // while a publisher has claimed it but not yet committed its mailbox
    // DATA. Count is allocated once and updated without boxing/locking at
    // the post-enqueue commit boundary.
    private final java.util.concurrent.atomic.AtomicInteger claimedPublications =
            new java.util.concurrent.atomic.AtomicInteger();
    // Cursor is scoped to this trusted graph session / generation.
    // Mutable one-element slots are preallocated once per graph source.
    // Generation changes reset values, NEVER erase selectors. Avoids the
    // post-reconfiguration missing-cursor bug and per-turn Integer boxing.
    private final Map<String, int[]> roundRobin = new HashMap<>();
    private long generation;
    private long nextFlowId = 1;
    private long nextRouteId = 1;
    private boolean closed;
    // VM-owned nonblocking mailbox publication scope, not a guest state loan.
    private boolean publicationInProgress;

    ActorPipelineRouteSession(ActorPipelineRoutePlan graph,
                                     long generation, int maxActiveFrames) {
        this(graph, generation, maxActiveFrames, Map.of());
    }

    ActorPipelineRouteSession(ActorPipelineRoutePlan graph,
                              long generation, int maxActiveFrames,
                              Map<String, ?> runtimeRecipients) {
        this.graph = Objects.requireNonNull(graph);
        if (generation < 1 || maxActiveFrames < 1 || maxActiveFrames > 65536) {
            throw new IllegalArgumentException("invalid route generation or frame budget");
        }
        this.runtimeRecipients = Map.copyOf(Objects.requireNonNull(runtimeRecipients));
        if (!this.runtimeRecipients.isEmpty()) {
            for (var edge : graph.edges()) {
                if (!this.runtimeRecipients.containsKey(edge.source())
                        || !this.runtimeRecipients.containsKey(edge.target()))
                    throw new IllegalArgumentException(
                            "bound routing session must cover all actor instances");
            }
        }
        this.generation = generation;
        this.capacity = maxActiveFrames;
        // Seed every source+generation RR cursor before actor routing begins.
        // Updating an existing HashMap entry during a turn must not allocate a
        // first-use selector node; this matches the other native/host backends.
        for (ActorPipelineRoutePlan.Edge edge : graph.edges()) {
            roundRobin.computeIfAbsent(edge.source(), ignored -> new int[1]);
        }
    }

    public synchronized long generation() { return generation; }
    public synchronized int outstanding() { return active.size(); }
    public synchronized boolean closed() { return closed; }

    /** VM-only validation for host-reported immutable correlation metadata. */
    synchronized void requireLive(MailFrame frame) {
        requireActive(frame);
    }

    /** Reserve exclusive host publication authority BEFORE any DATA
     * freezing/copying, quotas or mailbox write. A failure prior to actual
     * enqueue releases this claim and leaves the frame retryable. */
    static void claimMailboxAdmission(MailFrame frame) {
        if (frame != null) frame.session.claimPublishedFrame(frame);
    }

    private synchronized void claimPublishedFrame(MailFrame frame) {
        // Generation validation and claim are ONE admission operation:
        // a concurrent close/abort cannot fence the epoch in between.
        requireActive(frame);
        if (!frame.publicationState.compareAndSet(0, 1))
            throw new IllegalStateException(
                    "routed DATA already admitted or being published");
        claimedPublications.incrementAndGet();
    }

    /** Only called when the final mailbox enqueue did NOT succeed. */
    static void abortMailboxAdmission(MailFrame frame) {
        if (frame != null && frame.publicationState.compareAndSet(1, 0))
            frame.session.claimedPublications.decrementAndGet();
    }

    /** Exactly the successful bounded mailbox tryWrite linearization:
     * cannot allocate, acquire a session lock or throw after enqueue.
     * claimMailboxAdmission already validated and reserved the token. */
    static void markMailboxPublished(MailFrame frame) {
        if (frame != null) {
            // No allocation/monitor/exception between successful tryWrite
            // and this irreversible marker. The claimed-publication
            // counter is released only by its owning successful sender.
            int prior = frame.publicationState.getAndSet(2);
            if (prior == 1)
                frame.session.claimedPublications.decrementAndGet();
        }
    }

    static boolean wasMailboxPublished(MailFrame frame) {
        Objects.requireNonNull(frame, "route frame");
        return frame.publicationState.get() == 2;
    }

    /** Final DATA admission guard independent of the high-level router.
     * Reject wrong-actor, stale and selector-only route metadata before any
     * payload copy or mailbox slot reservation. */
    static void requireMailboxRecipient(MailFrame frame, Object actorId) {
        Objects.requireNonNull(frame, "route frame");
        frame.session.requireBoundRecipient(frame, actorId);
    }

    private synchronized void requireBoundRecipient(MailFrame frame, Object actorId) {
        requireActive(frame);
        Object expected = runtimeRecipients.get(frame.receiver());
        if (expected == null || !expected.equals(actorId)) {
            throw new SecurityException(
                    "routed DATA mailbox target does not match sealed actor binding");
        }
    }

    /** Fail-closed ownership predicate used when pairing a route frame with
     * its opaque mailbox turn authority. No caller receives the frame itself. */
    synchronized boolean ownsLive(MailFrame frame) {
        return frame != null
                && !closed
                && frame.session == this
                && frame.generation == generation
                && active.containsKey(frame);
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
        int[] position = roundRobin.get(current.receiver());
        if (position == null) throw new IllegalStateException("round-robin cursor missing");
        int cursor = position[0];
        position[0] = cursor + 1 == options.size() ? 0 : cursor + 1;
        // Attempt consumes a turn even when subsequent capacity admission fails.
        return child(current, options.get(cursor));
    }

    /** Group-backed round robin cycles over the COMPLETE immutable DAG edge
     * order, skipping inactive group members. A membership change therefore
     * never causes a cursor to point at an unverified actor instance. The
     * attempt consumes its selected turn even if admission subsequently fails.
     * Group snapshots are host-owned selector inputs, not new route authority.
     */
    synchronized MailFrame forwardRoundRobinEligible(
            MailFrame current, java.util.Set<String> eligible) {
        requireForward(current);
        Objects.requireNonNull(eligible, "eligible targets");
        List<ActorPipelineRoutePlan.Edge> options = graph.forwardAll(current.receiver());
        int[] position = roundRobin.get(current.receiver());
        if (position == null) throw new IllegalStateException("round-robin cursor missing");
        int cursor = position[0];
        for (int offset = 0; offset < options.size(); offset++) {
            int index = (cursor + offset) % options.size();
            var edge = options.get(index);
            if (eligible.contains(edge.target())) {
                position[0] = index + 1 == options.size() ? 0 : index + 1;
                return child(current, edge);
            }
        }
        throw new IllegalStateException(
                "no connected ActorGroup member is eligible for round robin");
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

    /** Atomic allocation of a *subsequence* of graph edges. A dynamic
     * ActorGroup may remove candidates, but may not add edges, reorder
     * recipients, repeat a branch, or silently select an empty set. */
    synchronized List<MailFrame> forwardSelected(
            MailFrame current, List<ActorPipelineRoutePlan.Edge> selected) {
        requireForward(current);
        Objects.requireNonNull(selected, "selected graph edges");
        var allowed = graph.forwardAll(current.receiver());
        if (selected.isEmpty())
            throw new IllegalStateException("no eligible actor pipeline recipients");
        int cursor = 0;
        for (var edge : selected) {
            Objects.requireNonNull(edge, "selected edge");
            while (cursor < allowed.size() && !allowed.get(cursor).equals(edge)) cursor++;
            if (cursor == allowed.size())
                throw new SecurityException(
                        "selected ActorGroup recipient is not an ordered graph edge");
            cursor++;
        }
        reserve(selected.size());
        reserveRouteIds(selected.size());
        var prepared = new ArrayList<MailFrame>(selected.size());
        long routeId = nextRouteId;
        for (var edge : selected) {
            prepared.add(new MailFrame(this, generation, current.flowId,
                    routeId++, extend(current.actorPath, edge.target())));
        }
        var ready = List.copyOf(prepared);
        try {
            for (var child : ready) active.put(child, Boolean.TRUE);
        } catch (RuntimeException | Error failed) {
            for (var child : ready) active.remove(child);
            throw failed;
        }
        nextRouteId = routeId;
        return ready;
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
                nextRouteId, current.actorPath, current.receiverIndex - 1, true,
                current);
        active.put(child, Boolean.TRUE);
        active.remove(current);
        nextRouteId++;
        return child;
    }

    /**
     * VM-only direct reply to the authenticated flow origin. The destination
     * is index zero of the immutable path; no actor-controlled target string
     * participates in selection. The current one-shot frame is consumed only
     * after the replacement frame is installed in the same session.
     */
    synchronized MailFrame returnToOrigin(MailFrame current) {
        requireActive(current);
        if (current.receiverIndex == 0)
            throw new IllegalStateException("pipeline origin cannot reply to itself");
        reserveRouteIds(1);
        MailFrame child = new MailFrame(this, generation, current.flowId,
                nextRouteId, current.actorPath, 0, true, current);
        active.put(child, Boolean.TRUE);
        active.remove(current);
        nextRouteId++;
        return child;
    }

    /** Roll back a reverse/reply selection whose mailbox admission failed
     * before publication. This is a VM-owned one-shot transition, not a guest
     * API. Route IDs remain spent; they are NEVER reused after rejection.
     * A retired generation cannot resurrect a previously consumed frame. */
    synchronized void restoreRejectedReturn(MailFrame original, MailFrame rejected) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(rejected, "rejected");
        ensureOpen();
        if (original.session != this || rejected.session != this
                || original.generation != generation
                || rejected.generation != generation
                || !rejected.returning
                || rejected.returnParent != original
                || rejected.rollbackConsumed
                || rejected.publicationState.get() == 2
                || original.flowId != rejected.flowId
                || !original.actorPath.equals(rejected.actorPath)
                || active.containsKey(original)) {
            throw new IllegalStateException(
                    "reverse routing admission cannot restore an unrelated/stale frame");
        }
        // The mailbox's rejection cleanup may already have retired the
        // attempted child envelope. The parent must still be restorable
        // after either pre-enqueue rejection or envelope.close() cleanup.
        // Failed attempts always spend their distinct route ID.
        active.put(original, Boolean.TRUE);
        active.remove(rejected);
        rejected.rollbackConsumed = true;
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
        for (int[] cursor : roundRobin.values()) cursor[0] = 0;
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
        for (int[] cursor : roundRobin.values()) cursor[0] = 0;
        generation = successor;
        return cancelled;
    }

    private void rejectPublicationFence() {
        if (publicationInProgress || claimedPublications.get() != 0) {
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
