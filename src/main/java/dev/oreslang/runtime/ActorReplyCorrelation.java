package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * Bounded, generation-fenced routing metadata for an Atto actor's replies.
 *
 * This registry never owns KV state or executes guest code. The sole trusted
 * consumer of an actor output stream must supply the actor's session/request
 * identity to {@link #reply}; ordinary NIO completion callbacks cannot mint
 * this authority. No callbacks or Future settlement execute while gate held.
 *
 * This is a runtime foundation, NOT yet a guest-visible multi-client server.
 */
public final class ActorReplyCorrelation implements AutoCloseable {
    public static final int MAX_SESSIONS = 32;
    public static final int MAX_IN_FLIGHT = 256;
    public static final int MAX_PER_SESSION = 16;

    /** A host-only session permit scoped to one storage actor generation. */
    public static final class Session {
        private final ActorReplyCorrelation owner;
        private final int id;
        private final long generation;
        private boolean active = true;
        private int pending;

        private Session(ActorReplyCorrelation owner, int id, long generation) {
            this.owner = owner;
            this.id = id;
            this.generation = generation;
        }

        public int id() { return id; }
        public long generation() { return generation; }
    }

    /** Typed, immutable output body; the Bytes value is already immutable. */
    public record Reply(int requestId, int sessionId, long generation,
                        String status, OresBytes payload) {
        public Reply {
            if (requestId <= 0 || sessionId <= 0 || generation <= 0)
                throw new IllegalArgumentException("invalid correlated reply identity");
            Objects.requireNonNull(status);
            Objects.requireNonNull(payload);
            if (!("OK".equals(status) || "NIL".equals(status)
                    || "VALUE".equals(status) || "ONE".equals(status)
                    || "ZERO".equals(status) || "ERR".equals(status)
                    || "PONG".equals(status)))
                throw new IllegalArgumentException("unexpected RESP2 reply status");
        }
    }

    /** Request ID is unique for the lifetime of the registry: never wraps. */
    public record Pending(int requestId, int sessionId, long generation,
                          OresFuture<Reply> response) { }

    private static final class Slot {
        final Session session;
        final OresFuture<Reply> result;

        Slot(Session session, OresFuture<Reply> result) {
            this.session = session;
            this.result = result;
        }
    }

    private final Object gate = new Object();
    private final Map<Integer, Session> sessions = new HashMap<>();
    private final Map<Integer, Slot> pending = new HashMap<>();
    private int nextSessionId = 1;
    private int nextRequestId = 1;
    private boolean closed;

    /** Reject admission instead of storing unbounded waiters or allocating. */
    public Optional<Session> openSession(long generation) {
        if (generation <= 0) throw new IllegalArgumentException("invalid generation");
        synchronized (gate) {
            if (closed || sessions.size() >= MAX_SESSIONS
                    || nextSessionId == Integer.MAX_VALUE)
                return Optional.empty();
            Session session = new Session(this, nextSessionId++, generation);
            sessions.put(session.id, session);
            return Optional.of(session);
        }
    }

    /**
     * Nonblocking try-admission: returns empty when credit is exhausted.
     * Never waits for a network write, actor turn, or mailbox slot.
     * Actual send must be admitted separately; on send failure cancel
     * this ticket before returning any error to the client.
     */
    public Optional<Pending> tryBegin(Session session) {
        Objects.requireNonNull(session);
        synchronized (gate) {
            if (session.owner != this || !session.active
                    || sessions.get(session.id) != session || closed)
                return Optional.empty();
            if (pending.size() >= MAX_IN_FLIGHT || session.pending >= MAX_PER_SESSION
                    || nextRequestId == Integer.MAX_VALUE)
                return Optional.empty();

            int id = nextRequestId++;
            OresFuture<Reply> response =
                    new OresFuture<>(() -> cancelForFuture(session, id), () -> { });
            pending.put(id, new Slot(session, response));
            session.pending++;
            return Optional.of(new Pending(id, session.id, session.generation, response));
        }
    }

    /**
     * Only the trusted single actor-output reader may call this. Numeric IDs
     * are routing keys, not proof of actor authority. Wrong-session/generation
     * or replayed replies are rejected WITHOUT consuming another pending call.
     */
    public boolean reply(Reply response) {
        Objects.requireNonNull(response);
        Slot slot;
        synchronized (gate) {
            slot = pending.get(response.requestId());
            if (slot == null || slot.session.id != response.sessionId()
                    || slot.session.generation != response.generation()
                    || !slot.session.active
                    || sessions.get(slot.session.id) != slot.session)
                return false;
            removeLocked(response.requestId(), slot);
        }
        if (!slot.result.completeFromRuntime(response))
            throw new IllegalStateException("reply/Future settlement invariant");
        return true;
    }

    /**
     * Resolve one typed output from the trusted actor's single output reader.
     * The destination session is looked up from a host-reserved request ID;
     * an actor never supplies a separately forgeable client destination.
     */
    public boolean replyFromActor(int requestId, long generation,
                                  String status, OresBytes payload) {
        final Slot slot;
        final Reply response;
        synchronized (gate) {
            slot = pending.get(requestId);
            if (slot == null || slot.session.generation != generation
                    || !slot.session.active
                    || sessions.get(slot.session.id) != slot.session)
                return false;
            // Validate the status and immutable payload BEFORE consuming credit.
            response = new Reply(requestId, slot.session.id, generation, status, payload);
            removeLocked(requestId, slot);
        }
        if (!slot.result.completeFromRuntime(response))
            throw new IllegalStateException("actor reply/Future settlement invariant");
        return true;
    }

    /** Future cancellation removes ticket before the Future's cancelled CAS.
     * Do NOT settle the Future in this admission callback. */
    private boolean cancelForFuture(Session session, int requestId) {
        synchronized (gate) {
            Slot slot = pending.get(requestId);
            if (slot == null || slot.session != session || session.owner != this)
                return false;
            removeLocked(requestId, slot);
            return true;
        }
    }

    /** Cancellation does not undo work already admitted to the KV actor. */
    public boolean cancelRequest(Session session, int requestId) {
        Objects.requireNonNull(session);
        Slot slot;
        synchronized (gate) {
            slot = pending.get(requestId);
            if (slot == null || slot.session != session
                    || session.owner != this)
                return false;
            removeLocked(requestId, slot);
        }
        if (!slot.result.failFromRuntime(
                new CancellationException("correlated request cancelled")))
            throw new IllegalStateException("cancelled reply/Future mismatch");
        return true;
    }

    /** Retire just one client; never cancel unrelated client responses. */
    public int closeSession(Session session) {
        Objects.requireNonNull(session);
        final ArrayList<Slot> discarded;
        synchronized (gate) {
            if (session.owner != this || !session.active
                    || sessions.get(session.id) != session)
                return 0;
            sessions.remove(session.id);
            session.active = false;
            discarded = removeSessionLocked(session);
        }
        failAll(discarded, new CancellationException("client session retired"));
        return discarded.size();
    }

    /** Fences late replies from a stopped storage actor incarnation. */
    public int retireGeneration(long generation) {
        if (generation <= 0) throw new IllegalArgumentException("invalid generation");
        final ArrayList<Slot> discarded = new ArrayList<>();
        synchronized (gate) {
            var iterator = sessions.values().iterator();
            while (iterator.hasNext()) {
                Session session = iterator.next();
                if (session.generation != generation) continue;
                iterator.remove();
                session.active = false;
                discarded.addAll(removeSessionLocked(session));
            }
        }
        failAll(discarded, new CancellationException("storage generation retired"));
        return discarded.size();
    }

    public int pendingCount() {
        synchronized (gate) { return pending.size(); }
    }

    public int sessionCount() {
        synchronized (gate) { return sessions.size(); }
    }

    private void removeLocked(int id, Slot slot) {
        if (pending.remove(id) != slot || slot.session.pending <= 0)
            throw new IllegalStateException("correlation registry credit invariant");
        slot.session.pending--;
    }

    private ArrayList<Slot> removeSessionLocked(Session session) {
        ArrayList<Slot> result = new ArrayList<>(session.pending);
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().session != session) continue;
            result.add(entry.getValue());
            iterator.remove();
        }
        if (result.size() != session.pending)
            throw new IllegalStateException("session reply accounting invariant");
        session.pending = 0;
        return result;
    }

    private static void failAll(ArrayList<Slot> slots, Throwable failure) {
        for (Slot slot : slots) {
            if (!slot.result.failFromRuntime(failure))
                throw new IllegalStateException("retired reply/Future mismatch");
        }
    }

    @Override public void close() {
        final ArrayList<Slot> discarded;
        synchronized (gate) {
            if (closed) return;
            closed = true;
            for (Session session : sessions.values()) {
                session.active = false;
                session.pending = 0;
            }
            discarded = new ArrayList<>(pending.values());
            pending.clear();
            sessions.clear();
        }
        failAll(discarded, new CancellationException("reply router closed"));
    }
}
