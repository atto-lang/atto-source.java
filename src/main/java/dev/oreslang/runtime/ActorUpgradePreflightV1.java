package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Trusted CONTROL-side preflight coupling for the actor code-generation gate
 * and the bounded, route-stable logical mailbox.
 *
 * <p>Freezes new generation work AND physical DATA dispatch. Bytes may still
 * be admitted to the bounded logical mailbox. Once previously dispatched
 * messages and external/suspended-task leases drain, this preflight reports
 * readiness for a later **atomic actor-route transaction**.</p>
 *
 * <p>This intentionally has NO publish() method. Independently committing
 * ActorUpgradeEpochGate and FreezeTicket is NOT an atomic route/image/state
 * cutover and risks split-brain state if the second commit fails. The future
 * router must own one linearizable generation+state+route publication step.
 * Until then this is only a reversible, fail-closed host admission barrier.</p>
 */
final class ActorUpgradePreflightV1 implements AutoCloseable {
    private final ActorUpgradeEpochGate generation;
    private final ActorUpgradeEpochGate.Candidate staged;
    private final LogicalActorProxyMailboxV1.FreezeTicket mailboxTicket;
    private boolean closed;

    private ActorUpgradePreflightV1(
            ActorUpgradeEpochGate generation,
            ActorUpgradeEpochGate.Candidate staged,
            LogicalActorProxyMailboxV1.FreezeTicket ticket) {
        this.generation = generation;
        this.staged = staged;
        this.mailboxTicket = ticket;
    }

    static ActorUpgradePreflightV1 prepare(
            ActorUpgradeEpochGate generation,
            LogicalActorProxyMailboxV1 mailbox,
            ActorUpgradeEpochGate.Image image) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(mailbox, "mailbox");
        Objects.requireNonNull(image, "code image");

        // Freeze new Task/code pin admission FIRST. If mailbox freeze fails,
        // the candidate must be aborted to restore old-route availability.
        ActorUpgradeEpochGate.Candidate staged = generation.prepare(image);
        try {
            return new ActorUpgradePreflightV1(
                    generation, staged, mailbox.freezeDispatchForUpgrade());
        } catch (RuntimeException | Error failure) {
            try {
                generation.abort(staged);
            } catch (RuntimeException | Error rollback) {
                if (failure != rollback) failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    /**
     * Stable pre-commit drain observation under existing admission freezes.
     * Not authorization to publish: state migration, actor turn/FFI provenance,
     * logical ActorRef CAS, protection-domain checks and physical worker
     * proof are still required.
     */
    synchronized boolean quiescentSnapshot() {
        requireOpen();
        if (!generation.ownsPrepared(staged))
            throw new IllegalStateException("actor generation preflight authority lost");
        return generation.livePins() == 0 && mailboxTicket.drained();
    }

    /**
     * The single authoritative CONTROL coordinator is the only caller.
     * This is intentionally NOT a public publish() API: a new actor route
     * must already have an immutable validated state/physical descriptor
     * before this method is entered. The callback is a plain field swap,
     * never guest code, I/O, or a physical worker startup.
     *
     * Lock order: coordinator -> preflight -> generation -> mailbox. This
     * holds BOTH independent admission locks until the generation image,
     * authoritative route descriptor, and mailbox epoch are committed.
     * Old mail completions and lease release cannot race the decision.
     */
    synchronized void commitWithAuthoritativeRoute(Runnable publishRoute) {
        requireOpen();
        Objects.requireNonNull(publishRoute, "authoritative route publication");
        synchronized (generation) {
            if (generation.livePins() != 0)
                throw new IllegalStateException(
                        "generation still has live turns, Tasks or external leases");
            mailboxTicket.commitWithPublication(() -> {
                if (!generation.publish(staged))
                    throw new IllegalStateException(
                            "generation unexpectedly became busy during publication");
                // Must be a single, allocation-free plain reference assignment.
                publishRoute.run();
            });
            closed = true; // consumed; close() must never roll a commit back
        }
    }

    /** Hold the old generation lock across validation, route CAS and retirement. */
    synchronized boolean withPreparedAuthority(java.util.function.BooleanSupplier commit) {
        requireOpen();
        return generation.withPreparedAuthority(staged, commit);
    }

    /** Frozen old-route DATA watermark captured under the mailbox lock. */
    synchronized long dispatchWatermark() {
        requireOpen();
        return mailboxTicket.dispatchWatermark();
    }

    /**
     * CONTROL-only post-publication retirement. This is NOT a second route
     * commit: the owning logical route snapshot was already atomically swapped.
     * Closing the old Task gate precedes re-enabling the new DATA route.
     * A failure is irreversible and requires the owner to quarantine DATA.
     */
    synchronized void finishPublished() {
        requireOpen();
        closed = true; // close() can never roll back a published route
        generation.close(); // prevents old generation pins, invalidates candidate
        mailboxTicket.commit(); // unfreeze only after authoritative route swap
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("upgrade preflight is closed");
    }

    /** Abort both freezes; never expose partial publication as success. */
    @Override
    public synchronized void close() {
        if (closed) return;
        // If one rollback fails, still attempt the other. A failed barrier
        // remains a supervisor-visible failure, not a successful upgrade.
        // Abort code publication FIRST. If that fails (for example a
        // separate holder already committed this image), reopening DATA
        // dispatch would route old messages under an unknown generation.
        // Preserve the frozen mailbox and escalate for supervisor recovery.
        try {
            generation.abort(staged);
        } catch (RuntimeException | Error unsafeAbort) {
            closed = true;
            throw unsafeAbort;
        }
        // Once old code is confirmed authoritative, reopening its original
        // mailbox epoch is safe. A failed rollback is reported and remains
        // frozen rather than falsely claiming progress.
        try {
            mailboxTicket.rollback();
        } finally {
            closed = true;
        }
    }
}
