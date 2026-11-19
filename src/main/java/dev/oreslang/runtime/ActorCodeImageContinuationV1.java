package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Host-only bridge from an authenticated physical image reservation into the
 * actor's existing bounded continuation ledger. The bridge is not a second
 * scheduler, actor route authority, image loader, or source-level capability.
 *
 * An admitted queued callback retains its exact code image through STOP,
 * normal/exceptional completion, and foreign cancellation until the ledger
 * transitions the callback to its actual terminal state.
 *
 * It does not pin actor message turns or unregistered FFI/native users:
 * physical unload still needs the independently authenticated loader witness.
 */
final class ActorCodeImageContinuationV1 {
    private final ActorContinuationLedger ledger;
    private final HotCodeGenerationRetentionV1 images;
    private final HotCodeGenerationRetentionV1.Ticket exactImage;

    ActorCodeImageContinuationV1(
            ActorContinuationLedger ledger,
            HotCodeGenerationRetentionV1 images,
            HotCodeGenerationRetentionV1.Ticket exactImage) {
        this.ledger = Objects.requireNonNull(ledger, "actor continuation ledger");
        this.images = Objects.requireNonNull(images, "physical image retention owner");
        this.exactImage = Objects.requireNonNull(exactImage, "physical code-image ticket");
    }

    ActorContinuationLedger.Admission admit(
            ActorContinuationLedger.Lane lane, Runnable callback) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(callback, "callback");
        // Lease acquisition and ledger transfer are separate monitors.
        // The image cannot be reclaimed in between: the acquired lease
        // forbids all physical cleanup until it becomes terminal.
        HotCodeGenerationRetentionV1.CodeLease lease = images.pinCode(exactImage);
        boolean transferred = false;
        try {
            ActorContinuationLedger.Admission receipt = ledger.admit(lane, callback, lease);
            transferred = true;
            return receipt;
        } finally {
            // Failed STOP/capacity/overflow admissions cannot strand a
            // physical-image pin without an owner that can retire it.
            if (!transferred) lease.close();
        }
    }
}
