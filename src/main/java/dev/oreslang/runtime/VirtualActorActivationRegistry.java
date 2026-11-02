package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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

    private static String requireName(String value, String kind) {
        Objects.requireNonNull(value, kind);
        if (value.isBlank() || value.length() > 128)
            throw new IllegalArgumentException("invalid virtual actor " + kind);
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i)))
                throw new IllegalArgumentException("control character in " + kind);
        }
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
        String canonical = Normalizer.normalize(key, Normalizer.Form.NFC);
        if (canonical.isEmpty() || canonical.indexOf('\u0000') >= 0 ||
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
                slots.put(identity, slot);
            }
            return new LogicalRef(slot);
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
     * Only the caller that performs actual actor initialization may publish
     * that incarnation to the runtime; this primitive tracks admission only.
     */
    public Activation activate(LogicalRef logical) {
        Objects.requireNonNull(logical, "logical");
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("virtual actor registry closed");
            Slot slot = logical.slot;
            if (slot.owner != this) throw new IllegalArgumentException("foreign virtual actor logical reference");
            boolean initializer = !slot.active;
            if (initializer) {
                if (nextIncarnation == Long.MAX_VALUE)
                    throw new IllegalStateException("virtual actor incarnation budget exhausted");
                slot.incarnation = ++nextIncarnation;
                slot.active = true;
                slot.published = false;
            }
            return new Activation(slot, slot.incarnation, initializer);
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
            slot.published = true;
        }
    }

    /** A failed initializer fences all pending handles for that incarnation. */
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

    public final class Admission implements AutoCloseable {
        private final Slot slot;
        private final long incarnation;
        private boolean finished;

        private Admission(Slot slot, long incarnation) {
            this.slot = slot;
            this.incarnation = incarnation;
        }
        public long incarnation() { return incarnation; }
        public void finish() {
            synchronized (monitor) {
                if (finished) return;
                finished = true;
                if (slot.outstanding < 1)
                    throw new IllegalStateException("virtual actor admission accounting corrupted");
                slot.outstanding--;
            }
        }
        @Override public void close() { finish(); }
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
            slot.outstanding++;
            return new Admission(slot, slot.incarnation);
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
            slot.active = false;
        }
    }

    public int outstanding(LogicalRef logical) {
        Objects.requireNonNull(logical, "logical");
        synchronized (monitor) {
            if (logical.slot.owner != this) throw new IllegalArgumentException("foreign virtual actor logical reference");
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
            closed = true;
            for (Slot slot : slots.values()) slot.active = false;
        }
    }
}
