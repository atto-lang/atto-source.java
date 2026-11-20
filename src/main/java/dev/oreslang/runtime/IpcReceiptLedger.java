package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Host-side, bounded IPC correlation primitive. This component does not
 * authenticate transport messages, perform network I/O, dispatch guest actors,
 * or provide durable deduplication. A trusted receiver must verify the entire
 * receipt identity before passing authenticated=true.
 */
public final class IpcReceiptLedger {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.-]{1,96}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public enum Stage {
        LOCAL_ADMITTED,
        REMOTE_ACCEPTED,
        HANDLER_COMMITTED,
        HANDLER_FAILED,
        REJECTED_BEFORE_ADMISSION,
        UNKNOWN
    }

    public record Envelope(String senderDomain, String senderActor, long senderIncarnation,
                           String destinationActor, long destinationIncarnation,
                           long routeEpoch, String transportSession, long messageSequence,
                           long frameSequence, long capabilityGeneration,
                           String payloadSha256) {
        public Envelope {
            for (String token : new String[] {senderDomain, senderActor,
                    destinationActor, transportSession}) {
                if (token == null || !TOKEN.matcher(token).matches()) {
                    throw new IllegalArgumentException("noncanonical IPC actor/session identity");
                }
            }
            if (senderIncarnation <= 0 || destinationIncarnation <= 0 || routeEpoch <= 0
                    || messageSequence <= 0 || frameSequence <= 0 || capabilityGeneration <= 0) {
                throw new IllegalArgumentException("IPC integer outside positive signed 63-bit range");
            }
            if (payloadSha256 == null || !SHA256.matcher(payloadSha256).matches()) {
                throw new IllegalArgumentException("IPC digest must be lowercase SHA-256");
            }
        }
    }

    public record Budgets(int inFlight, int transportCredits) { }
    private static final class Entry {
        private final Envelope envelope;
        private Stage stage = Stage.LOCAL_ADMITTED;
        private Entry(Envelope envelope) { this.envelope = envelope; }
    }

    private final int capacity;
    private final Map<Long, Entry> live = new HashMap<>();
    private Envelope streamAnchor;
    private long lastSequence;
    private long lastRouteEpoch;
    private long lastCapabilityGeneration;
    private int transportCredits;

    public IpcReceiptLedger(int capacity) {
        if (capacity < 1 || capacity > 65536) {
            throw new IllegalArgumentException("IPC capacity must be in [1,65536]");
        }
        this.capacity = capacity;
    }

    public synchronized void admit(Envelope envelope) {
        Objects.requireNonNull(envelope, "typed envelope required");
        if (streamAnchor != null && (!streamAnchor.senderDomain().equals(envelope.senderDomain())
                || !streamAnchor.senderActor().equals(envelope.senderActor())
                || streamAnchor.senderIncarnation() != envelope.senderIncarnation()
                || !streamAnchor.destinationActor().equals(envelope.destinationActor())
                || streamAnchor.destinationIncarnation() != envelope.destinationIncarnation())) {
            throw new IllegalStateException("IPC cross-stream or stale incarnation");
        }
        if (envelope.messageSequence() <= lastSequence) {
            throw new IllegalStateException("IPC duplicate or reordered message sequence");
        }
        if (envelope.routeEpoch() < lastRouteEpoch
                || envelope.capabilityGeneration() < lastCapabilityGeneration) {
            throw new IllegalStateException("IPC route or capability generation rollback");
        }
        if (live.size() >= capacity) {
            throw new IllegalStateException("IPC bounded correlation exhausted");
        }
        // All correlation fields are immutable; only mutate counters once the
        // new entry is successfully allocated and added to the bounded table.
        live.put(envelope.messageSequence(), new Entry(envelope));
        if (streamAnchor == null) streamAnchor = envelope;
        lastSequence = envelope.messageSequence();
        lastRouteEpoch = envelope.routeEpoch();
        lastCapabilityGeneration = envelope.capabilityGeneration();
        transportCredits++;
    }

    public synchronized void advance(Envelope envelope, Stage stage, boolean authenticated) {
        if (!authenticated) throw new IllegalStateException("IPC unauthenticated receipt");
        Objects.requireNonNull(envelope, "typed receipt identity required");
        Objects.requireNonNull(stage, "typed receipt phase required");
        Entry entry = live.get(envelope.messageSequence());
        if (entry == null || !entry.envelope.equals(envelope)) {
            throw new IllegalStateException("IPC stale, forged, or duplicate receipt");
        }
        if (entry.stage == Stage.LOCAL_ADMITTED) {
            if (stage == Stage.REMOTE_ACCEPTED) {
                entry.stage = stage;
                transportCredits--;
                return;
            }
            if (stage == Stage.REJECTED_BEFORE_ADMISSION || stage == Stage.UNKNOWN) {
                transportCredits--;
                live.remove(envelope.messageSequence());
                return;
            }
        } else if (entry.stage == Stage.REMOTE_ACCEPTED) {
            if (stage == Stage.HANDLER_COMMITTED || stage == Stage.HANDLER_FAILED
                    || stage == Stage.UNKNOWN) {
                live.remove(envelope.messageSequence());
                return;
            }
        }
        throw new IllegalStateException("IPC forbidden receipt transition");
    }

    /** A dropped connection leaves all live outcomes uncertain, never failed. */
    public synchronized int markTransportLost() {
        int unresolved = live.size();
        live.clear();
        transportCredits = 0;
        return unresolved;
    }

    public synchronized Budgets budgets() {
        return new Budgets(live.size(), transportCredits);
    }

    public synchronized boolean invariant() {
        if (transportCredits < 0 || transportCredits > live.size() || live.size() > capacity) {
            return false;
        }
        int expected = 0;
        for (Entry entry : live.values()) {
            if (entry.stage == Stage.LOCAL_ADMITTED) expected++;
            else if (entry.stage != Stage.REMOTE_ACCEPTED) return false;
        }
        return expected == transportCredits;
    }

    public static boolean canReplayAutomatically() { return false; }
}
