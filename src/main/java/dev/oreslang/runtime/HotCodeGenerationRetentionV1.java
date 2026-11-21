package dev.oreslang.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Supervisor-only memory/image admission budget for overlapping code images.
 *
 * This class intentionally does not route HTTP requests, unload an image, or
 * decide whether an actor is quiescent. It is a separate quota check to be
 * composed WITH the single authoritative route publisher and image loader.
 *
 * Critically, a third deployment NEVER purges the first generation to make
 * space. Promotion must fail/defer if all admitted slots are still occupied.
 * Even QUARANTINED images count against the limit.
 */
final class HotCodeGenerationRetentionV1 {
    enum Phase { STAGED, SERVING, DRAINING, QUARANTINED }

    record Blocker(long generation, String imageSha256, long reservedBytes, Phase phase) { }
    record Status(int maxImages, long maxBytes, long heldBytes,
                  List<Blocker> retained) { }

    /**
     * Trusted-loader cleanup evidence. This is intentionally tied to one
     * retention owner + exact Ticket identity, generation and image digest.
     * It is one-shot and cannot be replayed for another retained image.
     */
    static final class CleanupReceipt {
        private final HotCodeGenerationRetentionV1 owner;
        private final Ticket ticket;
        private final long imageGeneration;
        private final String imageSha256;
        private final String loaderResourceId;
        private boolean consumed;

        private CleanupReceipt(HotCodeGenerationRetentionV1 owner, Ticket ticket,
                               String loaderResourceId) {
            this.owner = owner;
            this.ticket = ticket;
            this.imageGeneration = ticket.generation;
            this.imageSha256 = ticket.imageSha256;
            if (loaderResourceId == null || loaderResourceId.isBlank()
                    || loaderResourceId.length() > 256)
                throw new IllegalArgumentException("bounded loader resource identity required");
            this.loaderResourceId = loaderResourceId;
        }

        long imageGeneration() { return imageGeneration; }
        String imageSha256() { return imageSha256; }
        String loaderResourceId() { return loaderResourceId; }
    }

    static final class Ticket {
        private final HotCodeGenerationRetentionV1 owner;
        private final long generation;
        private final String imageSha256;
        private final long reservedBytes;
        private Phase phase = Phase.STAGED;

        private Ticket(HotCodeGenerationRetentionV1 owner, long generation,
                       String imageSha256, long reservedBytes) {
            this.owner = owner;
            this.generation = generation;
            this.imageSha256 = imageSha256;
            this.reservedBytes = reservedBytes;
        }

        long generation() { return generation; }
        String imageSha256() { return imageSha256; }
    }

    private final int maxImages;
    private final long maxBytes;
    private final Map<Long, Ticket> retained = new LinkedHashMap<>();
    private long heldBytes;
    // Monotonic across collected images: generation IDs must never ABA-recycle.
    private long highestAdmittedGeneration;

    HotCodeGenerationRetentionV1(int maxImages, long maxBytes) {
        if (maxImages < 1 || maxBytes < 1)
            throw new IllegalArgumentException("retention budgets must be positive");
        this.maxImages = maxImages;
        this.maxBytes = maxBytes;
    }

    /**
     * Reserve capacity BEFORE staging/loading guest code. Caller owns actual
     * loader resources; it must release a rejected image outside any CONTROL
     * locks and not invoke guest callbacks here.
     */
    synchronized Ticket reserve(long generation, String imageSha256, long bytes) {
        if (generation < 1 || imageSha256 == null
                || !imageSha256.matches("[0-9a-f]{64}") || bytes < 1)
            throw new IllegalArgumentException("invalid code-image descriptor");
        if (generation <= highestAdmittedGeneration)
            throw new IllegalStateException("stale or reused code-image generation");
        // Subtract first to avoid overflow and keep failed admission atomic.
        if (retained.size() >= maxImages || bytes > maxBytes - heldBytes)
            throw new IllegalStateException("code-image retention budget exhausted; no forced purge");
        Ticket ticket = new Ticket(this, generation, imageSha256, bytes);
        retained.put(generation, ticket);
        heldBytes += bytes;
        highestAdmittedGeneration = generation;
        return ticket;
    }

    /** Call only AFTER external route publisher commits; no second route pointer. */
    synchronized void activated(Ticket ticket) {
        require(ticket, Phase.STAGED);
        ticket.phase = Phase.SERVING;
    }

    /** Old image remains present while admitted exchanges/code leases drain. */
    synchronized void draining(Ticket ticket) {
        require(ticket, Phase.SERVING);
        ticket.phase = Phase.DRAINING;
    }

    /**
     * Mint cleanup evidence only at the trusted loader boundary AFTER that
     * loader has physically released/revoked this exact image resource.
     * Package-private host infrastructure only; never surface this to guest.
     */
    synchronized CleanupReceipt physicalCleanupCompleted(
            Ticket ticket, String loaderResourceId) {
        require(ticket);
        if (ticket.phase == Phase.SERVING)
            throw new IllegalStateException(
                    "serving image cannot have a physical cleanup receipt");
        return new CleanupReceipt(this, ticket, loaderResourceId);
    }

    /** Failed image cleanup consumes budget and is NEVER silently discarded. */
    synchronized void quarantine(Ticket ticket) {
        require(ticket);
        if (ticket.phase == Phase.SERVING)
            throw new IllegalStateException("serving image must be detached before quarantine");
        ticket.phase = Phase.QUARANTINED;
    }

    /**
     * Observation only. Does not physically unload executable memory.
     *
     * The trusted loader must call this ONLY after cleanup was physically
     * confirmed. DRAINING images additionally require the HTTP-side drain to
     * be proven; true overall retirement also requires all independent actor,
     * closure/continuation/FFI/arena leases to be proven by the image loader.
     * A false proof deliberately leaves the image charged/quarantined.
     */
    synchronized void acknowledgePhysicalCleanup(
            Ticket ticket,
            long expectedRouteEpoch,
            HttpGenerationDrainV1 http,
            CleanupReceipt receipt) {
        require(ticket);
        if (expectedRouteEpoch < 1)
            throw new IllegalArgumentException("valid route epoch required");
        if (receipt == null || receipt.owner != this || receipt.ticket != ticket
                || receipt.imageGeneration != ticket.generation
                || !receipt.imageSha256.equals(ticket.imageSha256)
                || receipt.consumed)
            throw new IllegalStateException(
                    "stale, foreign, replayed or mismatched physical cleanup receipt");
        if (ticket.phase == Phase.SERVING)
            throw new IllegalStateException("cannot reclaim serving code");

        // STAGED images were never published and therefore may have no HTTP
        // generation. DRAINING/QUARANTINED images must prove the separately
        // versioned route epoch is physically drained; image generation is
        // deliberately NOT compared to route epoch.
        if (ticket.phase != Phase.STAGED) {
            if (http == null || http.generation() != expectedRouteEpoch
                    || !http.readyToRetire())
                throw new IllegalStateException("HTTP route generation still pinned or mismatched");
        } else if (http != null && http.generation() != expectedRouteEpoch) {
            throw new IllegalStateException("staged HTTP route generation mismatched");
        }

        retained.remove(ticket.generation);
        heldBytes -= ticket.reservedBytes;
        receipt.consumed = true;
    }

    synchronized Status status() {
        List<Blocker> blockers = retained.values().stream()
                .map(t -> new Blocker(t.generation, t.imageSha256,
                                      t.reservedBytes, t.phase))
                .toList();
        return new Status(maxImages, maxBytes, heldBytes, blockers);
    }

    private void require(Ticket ticket, Phase required) {
        require(ticket);
        if (ticket.phase != required)
            throw new IllegalStateException("unexpected code-image phase");
    }

    private void require(Ticket ticket) {
        if (ticket == null || ticket.owner != this
                || retained.get(ticket.generation) != ticket)
            throw new IllegalStateException("stale or foreign code-image ticket");
    }
}
