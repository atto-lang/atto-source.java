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
        LOCAL_ADMITTED, REMOTE_ACCEPTED, HANDLER_COMMITTED,
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
    private long lastRouteEpoch;
    private long lastCapabilityGeneration;
    private int transportCredits;

    public IpcReceiptCorrelation(int capacity) {
        if (capacity <= 0 || capacity > 65536) {
            throw new IllegalArgumentException("invalid bounded correlation capacity");
        }
        this.capacity = capacity;
    }

    public synchronized void admit(Envelope message) {
        Objects.requireNonNull(message, "message");
        Stream next = new Stream(message.domain(), message.sender(),
                message.senderIncarnation(), message.destination(),
                message.destinationIncarnation());
        if (stream != null && !stream.equals(next)) {
            throw new IllegalArgumentException("cross-stream or stale incarnation");
        }
        if (message.messageSequence() <= lastSequence) {
            throw new IllegalArgumentException("duplicate/reordered message sequence");
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
        lastRouteEpoch = message.routeEpoch();
        lastCapabilityGeneration = message.capabilityGeneration();
        ++transportCredits;
    }

    /** Only authenticated adapters may call this. THIS METHOD DOES NOT AUTHENTICATE. */
    public synchronized void acceptVerified(Envelope message, Stage next) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(next, "next");
        Entry entry = pending.get(message.messageSequence());
        if (entry == null || !entry.envelope.equals(message)) {
            throw new IllegalArgumentException("forged/stale/unmatched receipt");
        }
        if (entry.stage == Stage.LOCAL_ADMITTED) {
            if (next == Stage.REMOTE_ACCEPTED) {
                entry.stage = next;
                --transportCredits;
                return;
            }
            if (next == Stage.REJECTED_BEFORE_REMOTE || next == Stage.OUTCOME_UNKNOWN) {
                pending.remove(message.messageSequence());
                --transportCredits;
                return;
            }
        } else if (entry.stage == Stage.REMOTE_ACCEPTED) {
            if (next == Stage.HANDLER_COMMITTED || next == Stage.HANDLER_FAILED
                    || next == Stage.OUTCOME_UNKNOWN) {
                pending.remove(message.messageSequence());
                return;
            }
        }
        throw new IllegalArgumentException("illegal/duplicate IPC receipt transition");
    }

    /**
     * Local trusted EOF/disconnect: return exactly which correlations became
     * uncertain. Copies finish before any mutation; a throwing allocation
     * cannot partially retire correlations. No message is automatically resent.
     */
    public synchronized List<LostCorrelation> retireTransportLost(String trustedSession) {
        requireToken(trustedSession, "trustedSession");
        ArrayList<LostCorrelation> losses = new ArrayList<>();
        for (Map.Entry<Long, Entry> item : pending.entrySet()) {
            Entry entry = item.getValue();
            if (entry.envelope.session().equals(trustedSession)) {
                losses.add(new LostCorrelation(entry.envelope, entry.stage));
            }
        }
        // Materialize immutable return value before touching the table:
        // List.copyOf can allocate and throw OutOfMemoryError.
        List<LostCorrelation> immutableLosses = List.copyOf(losses);
        // No allocation or user callbacks in the mutation/commit phase.
        for (LostCorrelation loss : immutableLosses) {
            Entry removed = pending.remove(loss.envelope().messageSequence());
            if (removed.stage == Stage.LOCAL_ADMITTED) --transportCredits;
        }
        return immutableLosses;
    }

    public synchronized Counters counters() {
        return new Counters(pending.size(), transportCredits);
    }

    public synchronized boolean invariant() {
        if (transportCredits < 0 || transportCredits > pending.size()
                || pending.size() > capacity) return false;
        int awaitingReceipt = 0;
        for (Entry entry : pending.values()) {
            if (entry.stage == Stage.LOCAL_ADMITTED) ++awaitingReceipt;
            else if (entry.stage != Stage.REMOTE_ACCEPTED) return false;
        }
        return transportCredits == awaitingReceipt;
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
