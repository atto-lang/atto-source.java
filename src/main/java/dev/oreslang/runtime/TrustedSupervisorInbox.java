package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Trusted HOST-ONLY model of separate supervisor CONTROL and ordinary user lanes.
 * Not exposed to guest code, not a substitute for ActorRuntime route authentication.
 * Callers must bind from verified runtime/actor turn identity, never payload claims.
 */
final class TrustedSupervisorInbox implements AutoCloseable {
    enum Admission { ACCEPTED, REJECTED, ESCALATE_TO_PARENT }
    record FailureNotice(long childId, long incarnation, long sequence, long faultCode) {}

    static final class ChildBinding {
        private final TrustedSupervisorInbox origin;
        private final long childId;
        private final long incarnation;
        private boolean revoked;
        private boolean terminalReported;
        private long lastUserSequence;
        private long lastControlSequence;
        private ChildBinding(TrustedSupervisorInbox origin, long childId, long incarnation) {
            this.origin = origin;
            this.childId = childId;
            this.incarnation = incarnation;
        }
    }

    static final class UserDelivery {
        private final TrustedSupervisorInbox origin;
        private final ChildBinding sender;
        private final long sequence;
        private final byte[] payload;
        private boolean dispatched;
        private UserDelivery(TrustedSupervisorInbox origin, ChildBinding sender, long sequence, byte[] payload) {
            this.origin = origin;
            this.sender = sender;
            this.sequence = sequence;
            this.payload = payload;
        }
        long childId() { return sender.childId; }
        long incarnation() { return sender.incarnation; }
        long sequence() { return sequence; }
        byte[] payloadCopy() {
            synchronized (origin) {
                if (!dispatched || !origin.unsettled.containsKey(this) || origin.closed)
                    throw new IllegalStateException("delivery is not live");
                return payload.clone();
            }
        }
    }

    private final int controlLimit;
    private final int outstandingLimit;
    private final long outstandingBytesLimit;
    private final int maxEnvelopeBytes;
    private final ArrayDeque<FailureNotice> control = new ArrayDeque<>();
    private final ArrayDeque<UserDelivery> user = new ArrayDeque<>();
    private final IdentityHashMap<UserDelivery, Boolean> unsettled = new IdentityHashMap<>();
    private final Map<Long, ChildBinding> boundChildren = new HashMap<>();
    private long outstandingBytes;
    private boolean closed;
    private boolean overflowEscalation;

    TrustedSupervisorInbox(int controlLimit, int outstandingLimit, long outstandingBytesLimit, int maxEnvelopeBytes) {
        if (controlLimit < 1 || outstandingLimit < 1 || outstandingBytesLimit < 1
                || maxEnvelopeBytes < 1 || maxEnvelopeBytes > outstandingBytesLimit) {
            throw new IllegalArgumentException("invalid bounded supervisor capacities");
        }
        this.controlLimit = controlLimit;
        this.outstandingLimit = outstandingLimit;
        this.outstandingBytesLimit = outstandingBytesLimit;
        this.maxEnvelopeBytes = maxEnvelopeBytes;
    }

    /** Only the trusted runtime's lifecycle owner may call this method. */
    synchronized ChildBinding bindVerifiedChild(long childId, long incarnation) {
        checkOpen();
        if (overflowEscalation) throw new IllegalStateException("parent escalation pending");
        if (childId <= 0 || incarnation <= 0 || boundChildren.containsKey(childId)) {
            throw new IllegalArgumentException("invalid/duplicate live supervisor child binding");
        }
        ChildBinding binding = new ChildBinding(this, childId, incarnation);
        boundChildren.put(childId, binding);
        return binding;
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("supervisor inbox closed");
    }

    private boolean valid(ChildBinding b) {
        return b != null && b.origin == this && !b.revoked
                && boundChildren.get(b.childId) == b;
    }

    /** Reserve byte and message credits before publication; dequeue is NOT completion. */
    synchronized Admission offerUser(ChildBinding binding, long sequence, byte[] payload) {
        checkOpen();
        Objects.requireNonNull(payload, "immutable serialized payload");
        if (!valid(binding) || binding.terminalReported || overflowEscalation
                || sequence <= binding.lastUserSequence || sequence <= 0
                || payload.length > maxEnvelopeBytes || unsettled.size() >= outstandingLimit
                || outstandingBytes > outstandingBytesLimit - payload.length) {
            return Admission.REJECTED;
        }
        byte[] copy = payload.clone();
        UserDelivery delivery = new UserDelivery(this, binding, sequence, copy);
        unsettled.put(delivery, Boolean.TRUE);
        user.addLast(delivery);
        outstandingBytes += copy.length;
        binding.lastUserSequence = sequence;
        return Admission.ACCEPTED;
    }

    /** Physical runtime panic/exit ingress only: never accept a guest-created event. */
    synchronized Admission offerTrustedPanic(ChildBinding binding, long sequence, long faultCode) {
        checkOpen();
        if (!valid(binding) || binding.terminalReported || sequence <= 0
                || sequence <= binding.lastControlSequence) return Admission.REJECTED;
        if (control.size() >= controlLimit) {
            // Never claim acceptance for a lost fatal event. The runtime MUST
            // synchronously forward an authenticated overflow to the parent.
            overflowEscalation = true;
            binding.revoked = true;
            boundChildren.remove(binding.childId, binding);
            return Admission.ESCALATE_TO_PARENT;
        }
        control.addLast(new FailureNotice(binding.childId, binding.incarnation, sequence, faultCode));
        binding.terminalReported = true;
        binding.lastControlSequence = sequence;
        return Admission.ACCEPTED;
    }

    synchronized FailureNotice pollControl() { checkOpen(); return control.pollFirst(); }

    synchronized UserDelivery pollUser() {
        checkOpen();
        UserDelivery delivery = user.pollFirst();
        if (delivery != null) delivery.dispatched = true;
        return delivery;
    }

    /** Credits stay reserved across dequeue and are released once on completion. */
    synchronized void completeUser(UserDelivery delivery) {
        checkOpen();
        if (delivery == null || delivery.origin != this || !delivery.dispatched
                || unsettled.remove(delivery) == null) {
            throw new IllegalArgumentException("foreign, pending or duplicate delivery completion");
        }
        outstandingBytes -= delivery.payload.length;
        Arrays.fill(delivery.payload, (byte) 0);
    }

    /** Revokes immediately; drops only queued user data; dispatched work must settle. */
    synchronized void revoke(ChildBinding binding) {
        checkOpen();
        if (!valid(binding)) throw new IllegalArgumentException("foreign or retired child binding");
        binding.revoked = true;
        boundChildren.remove(binding.childId, binding);
        for (Iterator<UserDelivery> iter = user.iterator(); iter.hasNext();) {
            UserDelivery d = iter.next();
            if (d.sender == binding) {
                iter.remove();
                unsettled.remove(d);
                outstandingBytes -= d.payload.length;
                Arrays.fill(d.payload, (byte) 0);
            }
        }
    }

    synchronized long outstandingBytes() { checkOpen(); return outstandingBytes; }
    synchronized int outstandingMessages() { checkOpen(); return unsettled.size(); }
    synchronized boolean needsParentEscalation() { return overflowEscalation; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (ChildBinding b : boundChildren.values()) b.revoked = true;
        boundChildren.clear();
        for (UserDelivery d : unsettled.keySet()) Arrays.fill(d.payload, (byte) 0);
        user.clear();
        control.clear();
        unsettled.clear();
        outstandingBytes = 0;
    }
}
