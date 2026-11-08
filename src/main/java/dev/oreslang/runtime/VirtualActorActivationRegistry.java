package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

    private static final int MAX_KEY_BYTES = 256;
    private final Object monitor = new Object();
    private final Map<Identity, Slot> slots = new HashMap<>();
    private final String domain;
    private final int maxIdentities;
    private final int mailboxCapacity;
    private long nextIncarnation;
    private boolean closed;

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
        this.domain = requireName(domain, "domain");
        if (maxIdentities < 1 || mailboxCapacity < 1)
            throw new IllegalArgumentException("identity and mailbox bounds must be positive");
        this.maxIdentities = maxIdentities;
        this.mailboxCapacity = mailboxCapacity;
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
        if (value.isBlank() || value.length() > 128)
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
        // Bound before normalization and UTF-8 encoding to constrain hostile input.
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
                LogicalRef reference = new LogicalRef(slot);
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
    public Activation activate(LogicalRef logical) {
        Objects.requireNonNull(logical, "logical");
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("virtual actor registry closed");
            Slot slot = logical.slot;
            if (slot.owner != this)
                throw new IllegalArgumentException("foreign virtual actor logical reference");
            boolean initializer = !slot.active;
            if (initializer && nextIncarnation == Long.MAX_VALUE)
                throw new IllegalStateException("virtual actor incarnation budget exhausted");
            long candidateIncarnation = initializer ? nextIncarnation + 1 : slot.incarnation;
            Activation candidate = new Activation(slot, candidateIncarnation, initializer);
            if (initializer) {
                nextIncarnation = candidateIncarnation;
                slot.incarnation = candidateIncarnation;
                slot.active = true;
                slot.published = false;
            }
            return candidate;
        }
    }

    public void publish(Activation activation) {
        Objects.requireNonNull(activation, "activation");
        synchronized (monitor) {
            Slot slot = activation.slot;
            if (slot.owner != this || closed || !slot.active || slot.published ||
                    slot.incarnation != activation.incarnation || !activation.initializer)
                throw new IllegalStateException("activation publication denied");
            slot.published = true;
        }
    }

    /** A failed initializer fences all pending handles; its generation is burned. */
    public void abort(Activation activation) {
        Objects.requireNonNull(activation, "activation");
        synchronized (monitor) {
            Slot slot = activation.slot;
            if (slot.owner != this || closed || !slot.active || slot.published ||
                    slot.incarnation != activation.incarnation || !activation.initializer)
                throw new IllegalStateException("activation abort denied");
            slot.active = false;
        }
    }

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
                disposition = result;
                slot.outstanding--;
            }
        }
    }

    public Admission tryAdmit(Activation activation) {
        Objects.requireNonNull(activation, "activation");
        synchronized (monitor) {
            Slot slot = activation.slot;
            if (slot.owner != this || closed || !slot.active || !slot.published ||
                    slot.incarnation != activation.incarnation)
                throw new IllegalStateException("inactive virtual actor activation");
            if (slot.outstanding == mailboxCapacity) return null;
            Admission receipt = new Admission(slot, slot.incarnation);
            slot.outstanding++;
            return receipt;
        }
    }

    public void deactivate(Activation activation) {
        Objects.requireNonNull(activation, "activation");
        synchronized (monitor) {
            Slot slot = activation.slot;
            if (slot.owner != this || closed || !slot.active || !slot.published ||
                    slot.incarnation != activation.incarnation)
                throw new IllegalStateException("stale virtual actor activation");
            if (slot.outstanding != 0)
                throw new IllegalStateException("cannot deactivate with accepted work outstanding");
            slot.active = false;
        }
    }

    public int outstanding(LogicalRef logical) {
        Objects.requireNonNull(logical, "logical");
        synchronized (monitor) {
            if (logical.slot.owner != this)
                throw new IllegalArgumentException("foreign virtual actor logical reference");
            return logical.slot.outstanding;
        }
    }

    @Override public void close() {
        synchronized (monitor) {
            if (closed) return;
            for (Slot slot : slots.values())
                if (slot.outstanding != 0)
                    throw new IllegalStateException("cannot close with accepted work outstanding");
            closed = true;
            for (Slot slot : slots.values()) slot.active = false;
        }
    }
}
