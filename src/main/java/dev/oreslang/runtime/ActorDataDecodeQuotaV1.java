package dev.oreslang.runtime;

/**
 * Trusted host-owned concurrent DATA decode admission budget. One instance
 * must be shared by every session of a tenant; never constructed from wire data.
 * This accounts transient work only, not retained mailbox/receiver allocations.
 */
final class ActorDataDecodeQuotaV1 {
    private final long maxBytes;
    private final int maxActive;
    private long usedBytes;
    private int active;

    ActorDataDecodeQuotaV1(long maxBytes, int maxActive) {
        if (maxBytes <= 0 || maxBytes > 256L * 1024 * 1024 || maxActive <= 0 || maxActive > 4096)
            throw new IllegalArgumentException("invalid DATA decode quota");
        this.maxBytes = maxBytes;
        this.maxActive = maxActive;
    }
    synchronized Lease tryReserve(long bytes) {
        if (bytes <= 0 || bytes > maxBytes - usedBytes || active == maxActive)
            return null;
        // Construct lease before changing counters so allocation failure cannot leak credit.
        Lease lease = new Lease(bytes);
        usedBytes += bytes;
        active++;
        return lease;
    }
    synchronized long usedBytes() { return usedBytes; }
    synchronized int active() { return active; }

    final class Lease implements AutoCloseable {
        private final long bytes;
        private boolean live = true;
        private Lease(long bytes) { this.bytes = bytes; }
        @Override public void close() {
            synchronized (ActorDataDecodeQuotaV1.this) {
                if (!live) return;
                live = false;
                usedBytes -= bytes;
                active--;
            }
        }
    }
}
