package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Compiler/host-only boundary between actor upgrade admission and an owned
 * resumable scheduler Task. This is deliberately not a guest-callable API.
 *
 * <p>Acquire the actor's external code-generation pin BEFORE task admission.
 * Retire it only from the scheduler's physical quiescence hook, never from
 * cancellation or result-Future settlement. Awaited continuations reuse the
 * TaskRunner and its pin; a separately started child must acquire another.
 *
 * <p>The pin is the real ActorRuntime ingress-fence accounting mechanism, not
 * an independent second hot-upgrade gate. The caller must additionally prove
 * that the scheduler and lowered frame belong to the same actor/code domain.
 * Until that compiler proof and structured child-scope ownership are wired,
 * this bridge must not enable source-level task {} execution.</p>
 */
final class ActorHotUpgradeTaskBridgeV1 {
    private ActorHotUpgradeTaskBridgeV1() { }

    /**
     * A pinned frame has two distinct lifetimes: cancelable result
     * settlement and trusted physical retirement. Never use the former as
     * evidence for code-image unload or successful TaskScope joining.
     */
    static final class PinnedTask<T> {
        // Allocate this whole physical-exit witness BEFORE Task admission.
        // Constructing a record AFTER scheduler.start() could throw OOME
        // after the frame became runnable; that path must never unpin code.
        private final OresFuture<Void> physicallyExited = new OresFuture<>();
        private OresFuture<T> result;

        OresFuture<T> result() { return result; }
        OresFuture<Void> physicallyExited() { return physicallyExited; }

        void attach(OresFuture<T> admittedResult) {
            // scheduler.start guarantees a non-null result. Assignment is
            // allocation-free, and no new fallible post-admission work runs.
            result = admittedResult;
        }
    }

    static <T> OresFuture<T> startPinned(
            ActorRuntime runtime,
            ActorRuntime.ActorRef<?> actor,
            OresScheduler scheduler,
            OresScheduler.Task<T> frame) {
        return startPinnedTracked(runtime, actor, scheduler, frame).result();
    }

    static <T> PinnedTask<T> startPinnedTracked(
            ActorRuntime runtime,
            ActorRuntime.ActorRef<?> actor,
            OresScheduler scheduler,
            OresScheduler.Task<T> frame) {
        // This temporary bridge has no compiler-issued actor/task affinity
        // receipt. Do not let executing guest turns launch an unscoped sibling
        // or acquire a new old-generation pin through this host seam.
        if (ActorRuntime.inActorExecution()
                || OresScheduler.current() != null
                || OresScheduler.isSchedulerCarrierThread()
                || AsyncRuntime.isAsyncCarrierThread()) {
            throw new SecurityException(
                    "unscoped actor hot-upgrade Task admission is host-only");
        }
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(frame, "frame");

        // Same lifecycle lock as freezeUserIngressForHotUpgrade(): a freeze
        // either rejects this new child or observes its pin until physical exit.
        PinnedTask<T> tracked = new PinnedTask<>();
        ActorRuntime.HotUpgradeExternalLease generation =
                runtime.pinExternalWorkForHotUpgrade(actor);
        try {
            // Task metadata and exit witness were allocated before admission.
            // The scheduler callback retains the old generation until the
            // final physical carrier turn has exited.
            OresFuture<T> result = scheduler.start(frame, () -> {
                // Invoke this only from the scheduler's final physical
                // retirement callback; cancelled Future settlement may occur
                // earlier while a frame is still executing on its carrier.
                try {
                    generation.close();
                    tracked.physicallyExited().completeFromRuntime(null);
                } catch (RuntimeException | Error retirementFailure) {
                    // Do NOT forge a completed generation release. Keep the
                    // failure observable by the structured Task owner; the
                    // ActorRuntime pin remains live if its debit failed.
                    tracked.physicallyExited().failFromRuntime(retirementFailure);
                }
            });
            tracked.attach(result);
            return tracked;
        } catch (RuntimeException | Error rejectedBeforeAdmission) {
            // Scheduler.start throws only before registering the TaskRunner;
            // post-registration enqueue failures settle via onQuiescent.
            // Lease.close is idempotent for that defensive overlap.
            try {
                generation.close();
            } catch (RuntimeException | Error releaseFailure) {
                if (releaseFailure != rejectedBeforeAdmission) {
                    rejectedBeforeAdmission.addSuppressed(releaseFailure);
                }
            }
            throw rejectedBeforeAdmission;
        }
    }
}
