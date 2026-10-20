package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Host-side bounded receipt correlation; mirrors NativeIpcReceiptTable in
 * oreslang-source.llvm. Not an authenticated socket, proxy, or guest ABI.
 *
 * The real transport MUST authenticate peer/frame/capability before invoking
 * acceptVerified(). Transport loss never gives retry/replay authorization.
 */
public final class IpcReceiptCorrelation {
    public enum Stage {
        LOCAL_ADMITTED, DISPATCH_COMMITTED, REMOTE_ACCEPTED, HANDLER_COMMITTED,
        HANDLER_FAILED, REJECTED_BEFORE_REMOTE, OUTCOME_UNKNOWN
    }

    public record Envelope(
            String domain, String sender, String destination,
            String session, String payloadSha256,
            long senderIncarnation, long destinationIncarnation,
            long routeEpoch, long capabilityGeneration,
            long messageSequence, long frameSequence) {
        public Envelope {
            requireToken(domain, "domain");
            requireToken(sender, "sender");
            requireToken(destination, "destination");
            requireToken(session, "session");
            if (payloadSha256 == null || payloadSha256.length() != 64) {
                throw new IllegalArgumentException("invalid SHA-256 payload digest");
            }
            for (int i = 0; i < payloadSha256.length(); ++i) {
                char c = payloadSha256.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                    throw new IllegalArgumentException("noncanonical SHA-256 payload digest");
                }
            }
            if (senderIncarnation <= 0 || destinationIncarnation <= 0
                    || routeEpoch <= 0 || capabilityGeneration <= 0
                    || messageSequence <= 0 || frameSequence <= 0) {
                throw new IllegalArgumentException("nonpositive IPC identity/generation");
            }
        }
    }

    public record LostCorrelation(Envelope envelope, Stage lastObserved) {
        public LostCorrelation {
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(lastObserved, "lastObserved");
        }
    }
    public record Counters(int pending, int transportCredits) { }

    private record Stream(String domain, String sender, long senderIncarnation,
                          String destination, long destinationIncarnation) { }
    private static final class Entry {
        final Envelope envelope;
        Stage stage = Stage.LOCAL_ADMITTED;
        Entry(Envelope envelope) { this.envelope = envelope; }
    }

    private final int capacity;
    private final TreeMap<Long, Entry> pending = new TreeMap<>();
    private Stream stream;
    private long lastSequence;
    private long lastFrameSequence;
    private long lastRouteEpoch;
    private long lastCapabilityGeneration;
    private int transportCredits;

    public IpcReceiptCorrelation(int capacity) {
        if (capacity <= 0 || capacity > 65536) {
            throw new IllegalArgumentException("invalid bounded correlation capacity");
        }
        this.capacity = capacity;
    }

    public void admit(Envelope message) {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC receipt admission");
        Objects.requireNonNull(message, "message");
        synchronized (this) {
            Stream next = new Stream(message.domain(), message.sender(),
                    message.senderIncarnation(), message.destination(),
                    message.destinationIncarnation());
            if (stream != null && !stream.equals(next)) {
                throw new IllegalArgumentException("cross-stream or stale incarnation");
            }
            if (message.messageSequence() <= lastSequence) {
                throw new IllegalArgumentException("duplicate/reordered message sequence");
            }
            if (message.frameSequence() <= lastFrameSequence) {
                throw new IllegalArgumentException("duplicate/reordered frame sequence");
            }
            if (message.routeEpoch() < lastRouteEpoch
                    || message.capabilityGeneration() < lastCapabilityGeneration) {
                throw new IllegalArgumentException("route or capability rollback");
            }
            if (pending.size() >= capacity) {
                throw new IllegalStateException("bounded IPC correlation table exhausted");
            }
            // Complete throwing operations before publishing any progress fields.
            if (pending.putIfAbsent(message.messageSequence(), new Entry(message)) != null) {
                throw new IllegalArgumentException("duplicate message sequence");
            }
            stream = next;
            lastSequence = message.messageSequence();
            lastFrameSequence = message.frameSequence();
            lastRouteEpoch = message.routeEpoch();
            lastCapabilityGeneration = message.capabilityGeneration();
            ++transportCredits;
        }
    }

    /**
     * Trusted LOCAL dispatch commitment. The caller must acquire and validate
     * transport session/capability authority immediately before committing,
     * then use that same fenced transport ownership to issue the actual send.
     * No network side effects are permitted before this call succeeds.
     *
     * A failure after the commitment is indeterminate unless an authenticated
     * remote terminal result arrives: it MUST NOT be turned into a definitive
     * never-sent rejection. This ledger by itself cannot fence a real socket.
     */
    void commitDispatch(Envelope message) {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC dispatch commitment");
        Objects.requireNonNull(message, "message");
        synchronized (this) {
            Entry entry = pending.get(message.messageSequence());
            if (entry == null || !entry.envelope.equals(message)
                    || entry.stage != Stage.LOCAL_ADMITTED) {
                throw new IllegalArgumentException("invalid or duplicate IPC dispatch commitment");
            }
            entry.stage = Stage.DISPATCH_COMMITTED;
        }
    }

    /**
     * Only authenticated peer adapters may call this. THIS METHOD DOES NOT
     * AUTHENTICATE. OUTCOME_UNKNOWN is exclusively a *local* trusted
     * disconnect decision, never a remotely asserted ACK/terminal receipt.
     * A remote peer cannot manufacture uncertainty to evade durable DONE
     * verification or induce an early callback result.
     */
    public void acceptVerified(Envelope message, Stage next) {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC verified receipt transition");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(next, "next");
        synchronized (this) {
            if (next == Stage.OUTCOME_UNKNOWN || next == Stage.REJECTED_BEFORE_REMOTE) {
                throw new IllegalArgumentException(
                        "local transport decision cannot be asserted by peer receipt");
            }
            Entry entry = pending.get(message.messageSequence());
            if (entry == null || !entry.envelope.equals(message)) {
                throw new IllegalArgumentException("forged/stale/unmatched receipt");
            }
            if (entry.stage == Stage.DISPATCH_COMMITTED) {
                if (next == Stage.REMOTE_ACCEPTED) {
                    entry.stage = next;
                    --transportCredits;
                    return;
                }
            } else if (entry.stage == Stage.REMOTE_ACCEPTED) {
                if (next == Stage.HANDLER_COMMITTED || next == Stage.HANDLER_FAILED) {
                    pending.remove(message.messageSequence());
                    return;
                }
            }
            throw new IllegalArgumentException("illegal/duplicate IPC receipt transition");
        }
    }

    /**
     * Trusted LOCAL dispatcher rejects a message before dispatch commitment.
     * The same monitor serializes this with commitDispatch: once dispatch
     * wins, rejecting as never-sent is forbidden, even if send subsequently
     * fails. Never invoke this through a peer-derived message or guest DATA.
     * This deliberately does not accept a Stage argument, preventing callers
     * from relabeling local admission failure as a remote handler result.
     */
    void rejectBeforeRemote(Envelope message) {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC local receipt rejection");
        Objects.requireNonNull(message, "message");
        synchronized (this) {
            Entry entry = pending.get(message.messageSequence());
            if (entry == null || !entry.envelope.equals(message)
                    || entry.stage != Stage.LOCAL_ADMITTED) {
                throw new IllegalArgumentException(
                        "invalid local pre-remote IPC rejection");
            }
            pending.remove(message.messageSequence());
            --transportCredits;
        }
    }

    /**
     * Local trusted EOF/disconnect with an authenticated transport epoch,
     * not a guest-chosen or textual session name. Caller must prove the
     * physical session incarnation/route/grant before invoking this method.
     * A stale EOF cannot retire a new generation with a reused session string.
     * Copies finish before any mutation; a throwing allocation cannot
     * partially retire correlations. No message is automatically resent.
     */
    List<LostCorrelation> retireTransportLost(Envelope trustedEpoch) {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC transport-loss retirement");
        Objects.requireNonNull(trustedEpoch, "trustedEpoch");
        synchronized (this) {
            ArrayList<LostCorrelation> losses = new ArrayList<>();
            for (Map.Entry<Long, Entry> item : pending.entrySet()) {
                Entry entry = item.getValue();
                if (sameTransportEpoch(entry.envelope, trustedEpoch)) {
                    losses.add(new LostCorrelation(entry.envelope, entry.stage));
                }
            }
            // Materialize immutable return value before touching the table:
            // List.copyOf can allocate and throw OutOfMemoryError.
            List<LostCorrelation> immutableLosses = List.copyOf(losses);
            // No allocation or user callbacks in the mutation/commit phase.
            for (LostCorrelation loss : immutableLosses) {
                Entry removed = pending.remove(loss.envelope().messageSequence());
                if (removed.stage == Stage.LOCAL_ADMITTED
                        || removed.stage == Stage.DISPATCH_COMMITTED) --transportCredits;
            }
            return immutableLosses;
        }
    }

    /**
     * Prevent a delayed close from an old authenticated session incarnation,
     * route or grant from retiring messages on a reused textual session name.
     * Sequence and payload digest identify messages, not the transport epoch.
     */
    private static boolean sameTransportEpoch(Envelope a, Envelope b) {
        return a.domain().equals(b.domain())
                && a.sender().equals(b.sender())
                && a.destination().equals(b.destination())
                && a.session().equals(b.session())
                && a.senderIncarnation() == b.senderIncarnation()
                && a.destinationIncarnation() == b.destinationIncarnation()
                && a.routeEpoch() == b.routeEpoch()
                && a.capabilityGeneration() == b.capabilityGeneration();
    }

    public Counters counters() {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC receipt counters");
        synchronized (this) {
            return new Counters(pending.size(), transportCredits);
        }
    }

    public boolean invariant() {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC receipt invariant");
        synchronized (this) {
            if (transportCredits < 0 || transportCredits > pending.size()
                    || pending.size() > capacity) return false;
            int awaitingReceipt = 0;
            for (Entry entry : pending.values()) {
                if (entry.stage == Stage.LOCAL_ADMITTED
                        || entry.stage == Stage.DISPATCH_COMMITTED) ++awaitingReceipt;
                else if (entry.stage != Stage.REMOTE_ACCEPTED) return false;
            }
            return transportCredits == awaitingReceipt;
        }
    }

    private static void requireToken(String value, String name) {
        if (value == null || value.isEmpty() || value.length() > 96) {
            throw new IllegalArgumentException("invalid " + name);
        }
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c == '-' || c == '_' || c == '.')) {
                throw new IllegalArgumentException("noncanonical " + name);
            }
        }
    }
}
