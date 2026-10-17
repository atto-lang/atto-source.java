package dev.oreslang.runtime;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** JDK-only exact-source smoke; no Oreslang guest feature claim. */
public final class ActorReliabilityKernelSmoke {
    private static void yes(boolean b, String message) {
        if (!b) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        var clock = new AtomicLong(100);
        var kernel = new ActorReliabilityKernel(4, clock::get);
        var actor = new ActorReliabilityKernel.ActorKey(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                1);
        for (int i = 0; i < 10; i++) {
            kernel.record(ActorReliabilityKernel.EventKind.ACTOR_FAILED, actor,
                    ActorReliabilityKernel.FaultClass.HANDLER_FAILURE, i);
            clock.incrementAndGet();
        }
        var snapshot = kernel.snapshot();
        yes(snapshot.recentEvents().size() == 4, "ring must be bounded");
        yes(snapshot.overwrittenEvents() == 6, "overwrites must be counted");
        yes(snapshot.counters().get(ActorReliabilityKernel.EventKind.ACTOR_FAILED) == 10L,
                "counter must retain full count");

        var restartClock = new AtomicLong(0);
        var budget = new ActorReliabilityKernel.RestartBudget(3, 100, 50, restartClock::get);
        yes(budget.tryAcquire().admitted(), "restart 1");
        restartClock.set(10); yes(budget.tryAcquire().admitted(), "restart 2");
        restartClock.set(20); yes(budget.tryAcquire().admitted(), "restart 3");
        restartClock.set(30);
        var denied = budget.tryAcquire();
        yes(!denied.admitted() && denied.retryAfterNanos() == 50, "restart storm denied");
        restartClock.set(79); yes(!budget.tryAcquire().admitted(), "quarantine active");
        restartClock.set(120); yes(budget.tryAcquire().admitted(), "old restarts expired");

        var overload = new ActorReliabilityKernel.OverloadGate(100, 80, 40);
        yes(overload.admit(79, false) == ActorReliabilityKernel.OverloadGate.Admission.ACCEPT, "below high");
        yes(overload.admit(80, false) == ActorReliabilityKernel.OverloadGate.Admission.SHED, "shed high");
        yes(overload.admit(60, false) == ActorReliabilityKernel.OverloadGate.Admission.SHED, "hysteresis");
        yes(overload.admit(60, true) == ActorReliabilityKernel.OverloadGate.Admission.CONTROL_ACCEPT, "control lane");
        yes(overload.admit(40, false) == ActorReliabilityKernel.OverloadGate.Admission.ACCEPT, "recover low");

        var backoff = new ActorReliabilityKernel.BackoffPolicy(10, 1_000);
        for (int i = 0; i < 20; i++) {
            long a = backoff.delayNanos(i, 42);
            long b = backoff.delayNanos(i, 42);
            yes(a == b, "backoff replay must be deterministic");
            yes(a >= 1 && a <= 1_000, "backoff bounded");
        }

        System.out.println("ACTOR_RELIABILITY_KERNEL_PASS");
    }
}
