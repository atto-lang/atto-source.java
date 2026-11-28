package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * VM-only local virtual-actor identity and activation admission substrate.
 * This class neither executes guests nor delivers messages. Actual ActorRuntime
 * dispatch, supervisor CONTROL, persistence, and distributed placement require
 * separately verified integrations. No caller callback executes while locked.
 *
 * A logical reference is stable across activations; an activation capability
 * is bound to exactly one live incarnation. Every accepted admission must be
 * completed or explicitly failed by the higher-level runtime before
 * deactivation, so accepted work cannot be silently lost.
 */
public final class VirtualActorActivationRegistry implements AutoCloseable {
    public record Identity(String domain, String declaration, String key) { }

    /** Immutable point-in-time view; it contains no activation capability. */
    public record Inspection(Identity identity, long latestIncarnation,
                             boolean activationPresent, boolean published,
                             int outstanding, boolean registryClosed) { }

    /** Bounded immutable page over logical IDs; cursors are valid only at this revision. */
    public record Page(List<Inspection> entries, long revision,
                       Optional<Identity> nextAfter) {
        public Page {
            entries = List.copyOf(entries);
            Objects.requireNonNull(nextAfter, "nextAfter");
            if (revision < 0) throw new IllegalArgumentException("invalid directory revision");
        }
    }

    public static final class StaleDirectoryPageException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public StaleDirectoryPageException() {
            super("virtual actor directory changed; restart pagination from the first page");
        }
    }

    private static final int MAX_KEY_BYTES = 256;
    private final Object monitor = new Object();
    private final Map<Identity, Slot> slots = new HashMap<>();
    private final String domain;
    private final int maxIdentities;
    private final int mailboxCapacity;
    private final LongSupplier generationSupplier;
    private long nextIncarnation;
    private long directoryRevision;
    private boolean closed;

    private void advanceRevision() {
        directoryRevision = Math.addExact(directoryRevision, 1L);
    }

    private static final class Slot {
        final Identity identity;
        final VirtualActorActivationRegistry owner;
        long incarnation;
        boolean active;
        boolean published;
        int outstanding;

        Slot(Identity identity, VirtualActorActivationRegistry owner) {
            this.identity = identity;
            this.owner = owner;
        }
    }

    public VirtualActorActivationRegistry(String domain, int maxIdentities, int mailboxCapacity) {
        this(domain, maxIdentities, mailboxCapacity, null);
    }

    VirtualActorActivationRegistry(String domain, int maxIdentities, int mailboxCapacity,
                                   LongSupplier generationSupplier) {
        this.domain = requireName(domain, "domain");
        if (maxIdentities < 1 || mailboxCapacity < 1)
            throw new IllegalArgumentException("identity and mailbox bounds must be positive");
        this.maxIdentities = maxIdentities;
        this.mailboxCapacity = mailboxCapacity;
        this.generationSupplier = generationSupplier;
    }

    private static void checkUnicode(String value, String kind) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch))
                throw new IllegalArgumentException("control character in " + kind);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                    throw new IllegalArgumentException("unpaired UTF-16 surrogate in " + kind);
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("unpaired UTF-16 surrogate in " + kind);
            }
        }
    }

    private static String requireName(String value, String kind) {
        Objects.requireNonNull(value, kind);
        if (value.isBlank() || value.length() > 128
                || value.getBytes(StandardCharsets.UTF_8).length > 128
                || !Normalizer.isNormalized(value, Normalizer.Form.NFC))
            throw new IllegalArgumentException("invalid virtual actor " + kind);
        checkUnicode(value, kind);
        return value;
    }

    public final class LogicalRef {
        private final Slot slot;
        private LogicalRef(Slot slot) { this.slot = slot; }
        public Identity identity() { return slot.identity; }
    }

    public LogicalRef resolve(String declaration, String key) {
        declaration = requireName(declaration, "declaration");
        Objects.requireNonNull(key, "key");
        // A key must be bounded BEFORE normalization and UTF-8 encoding:
        // prohibit adversarial allocations from huge untrusted identifiers.
        if (key.length() > MAX_KEY_BYTES * 4)
            throw new IllegalArgumentException("virtual actor key exceeds pre-normalization budget");
        checkUnicode(key, "key");
        String canonical = Normalizer.normalize(key, Normalizer.Form.NFC);
        if (canonical.isEmpty() ||
                canonical.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("invalid virtual actor key");
        }
        Identity identity = new Identity(domain, declaration, canonical);
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("virtual actor registry closed");
            Slot slot = slots.get(identity);
            if (slot == null) {
                if (slots.size() == maxIdentities)
                    throw new IllegalStateException("virtual actor identity capacity exhausted");
                slot = new Slot(identity, this);
                // Build the outward capability before consuming a bounded
                // identity slot, so allocation failure leaves no orphan entry.
                LogicalRef reference = new LogicalRef(slot);
                advanceRevision();
                slots.put(identity, slot);
                return reference;
            }
            return new LogicalRef(slot);
        }
    }

    /**
     * Side-effect-free logical lookup for supervisor/diagnostic tooling.
     * Unlike resolve(), this never allocates an identity slot or activation.
     */
    public Optional<Inspection> inspectIdentity(Identity identity) {
        Objects.requireNonNull(identity, "identity");
        synchronized (monitor) {
            if (!domain.equals(identity.domain())) return Optional.empty();
            Slot slot = slots.get(identity);
            if (slot == null) return Optional.empty();
            return Optional.of(snapshot(slot));
        }
    }

    /** Side-effect-free inspection of a reference issued by this registry. */
    public Inspection inspect(LogicalRef logical) {
        Objects.requireNonNull(logical, "logical");
        synchronized (monitor) {
            if (logical.slot.owner != this)
                throw new IllegalArgumentException("foreign virtual actor logical reference");
            return snapshot(logical.slot);
        }
    }

    private Inspection snapshot(Slot slot) {
        return new Inspection(slot.identity, slot.incarnation, slot.active,
                slot.active && slot.published, slot.outstanding, closed);
    }

    /** Remove only a newly resolved but never activated slot during failed owner admission. */
    void discardUnactivated(LogicalRef logical) {
        Objects.requireNonNull(logical, "logical");
        synchronized (monitor) {
            Slot slot = logical.slot;
            if (slot.owner != this)
                throw new IllegalArgumentException("foreign virtual actor logical reference");
            if (slot.active || slot.published || slot.outstanding != 0)
                throw new IllegalStateException("cannot discard an activated virtual actor identity");
            if (slots.get(slot.identity) == slot) {
                advanceRevision();
                slots.remove(slot.identity, slot);
            }
        }
    }

    public Page listPage(int limit, long expectedRevision, Identity after) {
        if (limit < 1 || limit > 256)
            throw new IllegalArgumentException("virtual actor page size must be 1..256");
        if (expectedRevision < -1 || (after != null && expectedRevision < 0))
            throw new IllegalArgumentException("invalid virtual actor page cursor");
        Comparator<Identity> order = Comparator.comparing(Identity::declaration)
                .thenComparing(Identity::key);
        synchronized (monitor) {
            if (after != null && !domain.equals(after.domain()))
                throw new IllegalArgumentException("foreign virtual actor page cursor");
            if (expectedRevision >= 0 && expectedRevision != directoryRevision)
                throw new StaleDirectoryPageException();
            List<Slot> ordered = slots.values().stream()
                    .filter(slot -> after == null || order.compare(slot.identity, after) > 0)
                    .sorted(Comparator.comparing(slot -> slot.identity, order))
                    .limit(limit + 1L)
                    .toList();
            boolean more = ordered.size() > limit;
            int count = Math.min(limit, ordered.size());
            List<Inspection> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) entries.add(snapshot(ordered.get(i)));
            Optional<Identity> next = more && count > 0
                    ? Optional.of(ordered.get(count - 1).identity)
                    : Optional.empty();
            return new Page(entries, directoryRevision, next);
        }
    }

    public final class Activation {
        private final Slot slot;
        private final long incarnation;
        private final boolean initializer;

        private Activation(Slot slot, long incarnation, boolean initializer) {
            this.slot = slot;
            this.incarnation = incarnation;
            this.initializer = initializer;
        }

        public boolean initializer() { return initializer; }
        public Identity identity() { return slot.identity; }
        public long incarnation() { return incarnation; }
        public boolean active() {
            synchronized (monitor) {
                return !closed && slot.active && slot.published && slot.incarnation == incarnation;
            }
        }
        public void requireActive() {
            if (!active()) throw new IllegalStateException("stale virtual actor activation");
        }
    }

    /**
     * Duplicate activation requests observe the same live incarnation.
     * Only the caller performing initialization may publish the candidate.
     */
    /**
     * Duplicate activation requests observe the same live incarnation.
     * Only the caller that performs actual actor initialization may publish
     * that incarnation to the runtime; this primitive tracks admission only.
     */
    public Activation activate(LogicalRef logical) {
        Objects.requireNonNull(logical, "logical");
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("virtual actor registry closed");
            Slot slot = logical.slot;
            if (slot.owner != this)
                throw new IllegalArgumentException("foreign virtual actor logical reference");
            boolean initializer = !slot.active;
            if (initializer && generationSupplier == null && nextIncarnation == Long.MAX_VALUE)
                throw new IllegalStateException("virtual actor incarnation budget exhausted");
            long candidateIncarnation = initializer
                    ? (generationSupplier == null ? nextIncarnation + 1 : generationSupplier.getAsLong())
                    : slot.incarnation;
            if (initializer && (candidateIncarnation <= slot.incarnation || candidateIncarnation <= 0))
                throw new IllegalStateException("virtual actor generation is not monotonic");
            // Never mark an activation live before its capability is allocated.
            // Out-of-memory or constructor failure must not strand a slot as
            // active-but-unpublished with no initializer able to recover it.
            Activation candidate = new Activation(slot, candidateIncarnation, initializer);
            if (initializer) {
                advanceRevision();
                nextIncarnation = Math.max(nextIncarnation, candidateIncarnation);
                slot.incarnation = candidateIncarnation;
                slot.active = true;
                slot.published = false;
            }
            return candidate;
        }
    }

    /** Commit activation only after the guest instance has initialized successfully. */
    public void publish(Activation activation) {
        Objects.requireNonNull(activation, "activation");
        synchronized (monitor) {
            Slot slot = activation.slot;
            if (slot.owner != this || closed || !slot.active || slot.published ||
                    slot.incarnation != activation.incarnation || !activation.initializer)
                throw new IllegalStateException("activation publication denied");
            advanceRevision();
            slot.published = true;
        }
    }

    /** A failed initializer fences all pending handles; its generation is burned. */
    /** A failed initializer fences all pending handles for that incarnation. */
    public void abort(Activation activation) {
        Objects.requireNonNull(activation, "activation");
        synchronized (monitor) {
            Slot slot = activation.slot;
            if (slot.owner != this || closed || !slot.active || slot.published ||
                    slot.incarnation != activation.incarnation || !activation.initializer)
                throw new IllegalStateException("activation abort denied");
            advanceRevision();
            slot.active = false;
        }
    }

    /** An accepted turn must have an explicit observed disposition. */
    public enum Disposition { PROCESSED, FAILED_CONTROL_NOTIFIED, CANCELLED_NOTIFIED }

    public final class Admission {
        private final Slot slot;
        private final long incarnation;
        private Disposition disposition;

        private Admission(Slot slot, long incarnation) {
            this.slot = slot;
            this.incarnation = incarnation;
        }
        public long incarnation() { return incarnation; }

        /**
         * Called only after the handler completed or after an independently
         * recorded failure/cancellation notice. This method does NOT send
         * notices itself and cannot attest a backend's CONTROL transport.
         * No bare close()/drop path exists to hide accepted work.
         */
        public void settle(Disposition result) {
            Objects.requireNonNull(result, "result");
            synchronized (monitor) {
                if (disposition != null) {
                    if (disposition != result)
                        throw new IllegalStateException("contradictory admission disposition");
                    return;
                }
                if (slot.outstanding < 1)
                    throw new IllegalStateException("virtual actor admission accounting corrupted");
                advanceRevision();
                disposition = result;
                slot.outstanding--;
            }
        }
    }

    /** Null means bounded backpressure; an inactive/stale capability fails closed. */
    public Admission tryAdmit(Activation activation) {
        Objects.requireNonNull(activation, "activation");
        synchronized (monitor) {
            Slot slot = activation.slot;
            if (slot.owner != this || closed || !slot.active || !slot.published ||
                    slot.incarnation != activation.incarnation)
                throw new IllegalStateException("inactive virtual actor activation");
            if (slot.outstanding == mailboxCapacity) return null;
            Admission receipt = new Admission(slot, slot.incarnation);
            advanceRevision();
            slot.outstanding++;
            return receipt;
        }
    }

    /** Refuse to deactivate with admitted work still outstanding. */
    public void deactivate(Activation activation) {
        Objects.requireNonNull(activation, "activation");
        synchronized (monitor) {
            Slot slot = activation.slot;
            if (slot.owner != this || closed || !slot.active || !slot.published ||
                    slot.incarnation != activation.incarnation)
                throw new IllegalStateException("stale virtual actor activation");
            if (slot.outstanding != 0)
                throw new IllegalStateException("cannot deactivate with accepted work outstanding");
            advanceRevision();
            slot.active = false;
        }
    }

    /*
     * Host-internal, LOCAL-only observation. A future supervisor directory must
     * authenticate the principal and its direct-child scope BEFORE calling this.
     * This is neither a guest API nor a distributed address/authority lookup.
     * An unknown ID is never implicitly registered by inspection.
     */
    enum LocalPhase { INACTIVE, INITIALIZING, ACTIVE, CLOSED }
    record LocalObservation(Identity identity, long incarnation,
                            LocalPhase phase, int admitted) { }

    LocalObservation inspectExistingForHost(Identity requested) {
        Objects.requireNonNull(requested, "requested");
        // Caller-supplied IDs are bounded before HashMap hash/equals work.
        // The VM-local registry's canonical identity is NOT the ActorId v1 wire ABI.
        if (!domain.equals(requested.domain())
                || requested.declaration() == null || requested.key() == null
                || requested.declaration().length() > 128
                || requested.key().length() > MAX_KEY_BYTES * 4
                || !Normalizer.isNormalized(requested.key(), Normalizer.Form.NFC)) {
            return null;
        }
        synchronized (monitor) {
            Slot slot = slots.get(requested);
            if (slot == null) return null;
            LocalPhase phase = closed ? LocalPhase.CLOSED
                    : !slot.active ? LocalPhase.INACTIVE
                    : slot.published ? LocalPhase.ACTIVE : LocalPhase.INITIALIZING;
            // An immutable point-in-time value; never a live Slot, Activation,
            // lease, mailbox, or mutable actor heap reference.
            return new LocalObservation(slot.identity, slot.incarnation,
                    phase, slot.outstanding);
        }
    }

    LocalObservation inspectForHost(LogicalRef logical) {
        Objects.requireNonNull(logical, "logical");
        if (logical.slot.owner != this)
            throw new IllegalArgumentException("foreign virtual actor logical reference");
        return inspectExistingForHost(logical.slot.identity);
    }

    public int outstanding(LogicalRef logical) {
        Objects.requireNonNull(logical, "logical");
        synchronized (monitor) {
            if (logical.slot.owner != this)
                throw new IllegalArgumentException("foreign virtual actor logical reference");
            return logical.slot.outstanding;
        }
    }

    /** Force-close is intentionally absent: no accepted work is discarded. */
    @Override public void close() {
        synchronized (monitor) {
            if (closed) return;
            for (Slot slot : slots.values())
                if (slot.outstanding != 0)
                    throw new IllegalStateException("cannot close with accepted work outstanding");
            advanceRevision();
            closed = true;
            for (Slot slot : slots.values()) slot.active = false;
        }
    }
}
