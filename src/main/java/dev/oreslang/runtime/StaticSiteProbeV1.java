package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Java-only bounded recorder for already verified static callsites.
 * No guest parser, automatic log lowering, Truffle JIT, exporter or host authority.
 * Only static identity and optional validated W3C context enter the queue:
 * no guest message, object, body or arbitrary value can be retained here.
 *
 * Multiple actor carriers may publish concurrently; consumers are expected
 * to run outside the actor carrier. No locks, blocking I/O, or unbounded queues.
 * A saturated slot drops/replaces its previous receipt; ordering is best effort.
 */
final class StaticSiteProbeV1 {
    record Receipt(SourceIdentityV1.Site source, String traceId, String spanId) {}

    private final AtomicReferenceArray<Receipt> slots;
    private final AtomicLong next = new AtomicLong();
    private final AtomicLong overwritten = new AtomicLong();
    private final AtomicInteger pollCursor = new AtomicInteger();
    private volatile boolean recording;

    StaticSiteProbeV1(int capacity) {
        if (capacity < 1 || capacity > 1024) {
            throw new IllegalArgumentException("probe capacity must be 1..1024");
        }
        slots = new AtomicReferenceArray<>(capacity);
    }

    // Only trusted runtime code in the same package should call this.
    void setRecording(boolean recording) { this.recording = recording; }

    private static boolean validContextId(String value, int digits) {
        if (value == null || value.length() != digits) return false;
        boolean nonzero = false;
        for (int i = 0; i < digits; ++i) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
            nonzero |= c != '0';
        }
        return nonzero;
    }

    private void drop() {
        overwritten.getAndUpdate(n -> n == Long.MAX_VALUE ? n : n + 1);
    }

    boolean emit(SourceIdentityV1.Site source, String traceId, String spanId) {
        if (!recording) return false; // No allocation, clock or context lookup.
        Objects.requireNonNull(source, "verified compiler static descriptor");
        boolean missing = "".equals(traceId) && "".equals(spanId);
        if (!missing && (!validContextId(traceId, 32) || !validContextId(spanId, 16))) {
            throw new IllegalArgumentException("incomplete/invalid W3C execution context");
        }
        long ordinal;
        do {
            ordinal = next.get();
            if (ordinal == Long.MAX_VALUE) {
                drop();
                return false;
            }
        } while (!next.compareAndSet(ordinal, ordinal + 1));
        int slot = (int) (ordinal % slots.length());
        Receipt old = slots.getAndSet(slot, new Receipt(source, traceId, spanId));
        if (old != null) drop();
        return true;
    }

    Receipt poll() {
        int start = pollCursor.getAndUpdate(n -> (n + 1) % slots.length());
        for (int i = 0; i < slots.length(); ++i) {
            Receipt receipt = slots.getAndSet((start + i) % slots.length(), null);
            if (receipt != null) return receipt;
        }
        return null;
    }

    long emitted() { return next.get(); }
    long dropped() { return overwritten.get(); }
    int capacity() { return slots.length(); }
}
