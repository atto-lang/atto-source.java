package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Trusted-host bridge between the actor code-generation fence and the
 * scheduler's physical Task-exit barrier.
 *
 * <p>Every NEW child Task has a distinct generation pin acquired before the
 * scheduler can admit its continuation. An ordinary `suspends` call resumes
 * inside an existing Task and MUST NOT call this bridge again.</p>
 *
 * <p>NOT a guest API. A successful pin does not grant actor-state access,
 * mailbox affinity, quotas, capture ownership or sandbox placement. Those
 * authorizations remain the responsibility of compiler-owned actor admission.
 * Consequently this class is deliberately not called from guest source
 * until actor frame lowering is verified.</p>
 */
final class ActorTaskGenerationBridge {
    private ActorTaskGenerationBridge() { }

    static <T> OresTask<T> start(
            ActorUpgradeEpochGate actor,
            OresTaskScope owner,
            OresScheduler scheduler,
            OresScheduler.Task<T> continuation) {
        Objects.requireNonNull(actor, "actor generation");
        Objects.requireNonNull(owner, "Task scope");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(continuation, "continuation");

        // Linearize the code-image lease before scheduling. prepare() freezes
        // pin acquisition and cannot publish over an admitted suspended Task.
        ActorUpgradeEpochGate.Lease image =
                actor.pin(ActorUpgradeEpochGate.LeaseKind.SUSPENDED_TASK);

        // This overload owns the lease on BOTH paths: scheduler/scope
        // rejection runs the callback immediately, while successful admission
        // transfers its release to the scheduler's final physical-exit hook.
        // Do not wrap with try/finally: that could unload code while awaiting.
        return owner.startCompilerFrame(scheduler, continuation, image::close);
    }
}
