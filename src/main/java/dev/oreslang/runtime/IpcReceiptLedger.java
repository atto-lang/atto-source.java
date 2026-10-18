package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Bounded host-side IPC receipt correlation. Not an actor transport.
 *
 * <p>Only a trusted, independently authenticated adapter may call
 * {@link #advanceVerified}. This class never verifies a peer credential,
 * dispatches guest code, persists replay state, or guarantees durable effects.
 * A missing receipt after a possible transport write is UNKNOWN, not failure.
 * No guest-facing method or user-provided boolean grants authentication.</p>
 */
public final class IpcReceiptLedger {
    private static final int MAX_CORRELATIONS = 65_536;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.-]{1,96}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public enum Stage {
        LOCAL_ADMITTED, REMOTE_ACCEPTED, HANDLER_COMMITTED, HANDLER_FAILED,
        REJECTED_BEFORE_REMOTE, OUTCOME_UNKNOWN
    }

    public record ReceiptKey(String domain, String sender, String destination,
                             String session, String sha256,
                             long senderIncarnation, long destinationIncarnation,
                             long routeEpoch, long messageSequence,
                             long frameSequence, long capabilityGeneration) {
        public ReceiptKey {
            for (String field : new String[]{domain, sender, destination, session}) {
                if (field == null || !TOKEN.matcher(field).matches()) {
                    throw new IllegalArgumentException("invalid IPC actor/session token");
                }
            }
            if (sha256 == null || !HASH.matcher(sha256).matches()) {
                throw new IllegalArgumentException("payload digest must be exact lowercase SHA-256");
            }
            if (senderIncarnation <= 0 || destinationIncarnation <= 0 ||
                    routeEpoch <= 0 || messageSequence <= 0 ||
                    frameSequence <= 0 || capabilityGeneration <= 0) {
                throw new IllegalArgumentException("IPC sequence/generation must be positive signed i64");
            }
        }
    }

    public record Budgets(int inFlight, int transportCredits) { }

    private record StreamIdentity(String domain, String sender, String destination,
                                  long senderIncarnation, long destinationIncarnation) { }

    private static final class Entry {
        final ReceiptKey key;
        Stage stage;
        Entry(ReceiptKey key) {
            this.key = key;
            this.stage = Stage.LOCAL_ADMITTED;
        }
    }

    private final int capacity;
    private final Map<Long, Entry> entries = new HashMap<>();
    private StreamIdentity stream;
    private long lastSequence;
    private long lastRouteEpoch;
    private long lastCapabilityGeneration;
    private int transportCredits;

    public IpcReceiptLedger(int capacity) {
        if (capacity <= 0 || capacity > MAX_CORRELATIONS) {
            throw new IllegalArgumentException("IPC correlation capacity must be 1..65536");
        }
        this.capacity = capacity;
    }

    synchronized void admit(ReceiptKey key) {
        Objects.requireNonNull(key, "receipt key");
        StreamIdentity candidate = new StreamIdentity(key.domain(), key.sender(),
                key.destination(), key.senderIncarnation(), key.destinationIncarnation());
        if (stream != null && !stream.equals(candidate)) {
            throw new IllegalStateException("cross-stream or stale incarnation");
        }
        if (key.messageSequence() <= lastSequence) {
            throw new IllegalStateException("duplicate/reordered IPC message sequence");
        }
        if (key.routeEpoch() < lastRouteEpoch ||
                key.capabilityGeneration() < lastCapabilityGeneration) {
            throw new IllegalStateException("IPC route or capability rollback");
        }
        if (entries.size() >= capacity) {
            throw new IllegalStateException("bounded IPC correlation capacity exhausted");
        }
        // Do all potentially allocating work before counters are published.
        entries.put(key.messageSequence(), new Entry(key));
        if (stream == null) stream = candidate;
        lastSequence = key.messageSequence();
        lastRouteEpoch = key.routeEpoch();
        lastCapabilityGeneration = key.capabilityGeneration();
        transportCredits++;
    }

    /**
     * Only trusted runtime-package code may authenticate and supply the peer outside this
     * method. A host reference to this object is never itself an auth capability.
     */
    synchronized void advanceVerified(ReceiptKey key, Stage next) {
        Objects.requireNonNull(key, "receipt key");
        Objects.requireNonNull(next, "stage");
        Entry entry = entries.get(key.messageSequence());
        if (entry == null || !entry.key.equals(key)) {
            throw new IllegalStateException("stale, forged, or duplicate IPC receipt");
        }
        if (entry.stage == Stage.LOCAL_ADMITTED) {
            if (next == Stage.REMOTE_ACCEPTED) {
                entry.stage = next;
                transportCredits--;
                return;
            }
            if (next == Stage.REJECTED_BEFORE_REMOTE || next == Stage.OUTCOME_UNKNOWN) {
                transportCredits--;
                entries.remove(key.messageSequence());
                return;
            }
        } else if (entry.stage == Stage.REMOTE_ACCEPTED) {
            if (next == Stage.HANDLER_COMMITTED || next == Stage.HANDLER_FAILED ||
                    next == Stage.OUTCOME_UNKNOWN) {
                entries.remove(key.messageSequence());
                return;
            }
        }
        throw new IllegalStateException("illegal or duplicate IPC receipt transition");
    }

    /** An unresolved transport outcome cannot become a definitive failure. */
    synchronized int markTransportLost() {
        int unknown = entries.size();
        entries.clear();
        transportCredits = 0;
        return unknown;
    }

    public synchronized Budgets budgets() {
        return new Budgets(entries.size(), transportCredits);
    }

    public synchronized boolean invariant() {
        if (entries.size() > capacity || transportCredits > entries.size()) return false;
        int pending = 0;
        for (Entry entry : entries.values()) {
            if (entry.stage == Stage.LOCAL_ADMITTED) pending++;
            else if (entry.stage != Stage.REMOTE_ACCEPTED) return false;
        }
        return pending == transportCredits;
    }

    public static boolean mayReplayAutomatically() { return false; }
}
