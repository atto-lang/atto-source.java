package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** No new actor quota may be minted by await, cooperate or carrier migration. */
@Timeout(10)
final class UntrustedLogicalQuotaTest {
    // Host-side white-box test probes. Never capture these in an UNTRUSTED
    // BehaviorFactory/Behavior instance: factories must remain stateless.
    // This fixture instrumentation is not a guest-accessible capability API.
    private record SiblingProbe(
            AtomicReference<OresFuture<Integer>> retained,
            AtomicReference<Throwable> secondRejected,
            AtomicReference<Throwable> physicalBudgetDenied,
            java.util.concurrent.CountDownLatch attempted,
            OresFuture<Integer> wait) { }
    private record NestedProbe(AtomicReference<OresFuture<Integer>> outerRef) { }
    private record FuelProbe(
            AtomicReference<OresFuture<Integer>> taskRef,
            AtomicInteger dispatches) { }
    private record DeadlineProbe(
            AtomicReference<OresFuture<Integer>> taskRef,
            AtomicInteger resumes,
            OresFuture<Integer> awaited) { }

    private static volatile SiblingProbe siblingProbe;
    private static volatile NestedProbe nestedProbe;
    private static volatile FuelProbe fuelProbe;
    private static volatile DeadlineProbe deadlineProbe;

    @Test
    void fuelDebtSurvivesAnyNumberOfCooperateHandoffs() {
        AtomicLong wall = new AtomicLong();
        AtomicLong cpu = new AtomicLong(100);
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                1_000_000L, 3, 0, 0, wall::get, cpu::get);

        for (int turn = 0; turn < 3; turn++) {
            quota.enter();
            quota.chargeSafepoint();
            quota.leave();
            // Every re-entry represents a fresh carrier dispatch with a new
            // per-thread CPU counter; fuel must NOT be reinitialized.
            cpu.addAndGet(100);
        }
        quota.enter();
        try {
            var exceeded = assertThrows(
                    ActorRuntime.UntrustedActorQuotaExceededException.class,
                    quota::chargeSafepoint);
            assertEquals(
                    ActorRuntime.UntrustedActorQuotaExceededException.Resource.FUEL,
                    exceeded.resource());
        } finally {
            quota.leave();
        }
    }

    @Test
    void wallDeadlineIncludesTimeSuspendedOnFuture() {
        AtomicLong wall = new AtomicLong(10);
        AtomicLong cpu = new AtomicLong(100);
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                10, 10, 10, 0, wall::get, cpu::get);
        quota.enter();
        quota.check();
        quota.leave();
        wall.set(21);
        cpu.set(1000); // fake a different carrier on resumption
        quota.enter();
        try {
            var exceeded = assertThrows(
                    ActorRuntime.UntrustedActorQuotaExceededException.class,
                    quota::check);
            assertEquals(
                    ActorRuntime.UntrustedActorQuotaExceededException.Resource.WALL_TIME,
                    exceeded.resource());
        } finally {
            quota.leave();
        }
    }

    @Test
    void cpuDebtAccumulatesAcrossUnrelatedCarrierThreadCpuClocks() {
        AtomicLong wall = new AtomicLong();
        AtomicLong cpu = new AtomicLong(100);
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                10, 10, 0, 0, wall::get, cpu::get);
        quota.enter();
        cpu.set(106);
        quota.check(); // 6 CPU ns consumed on first carrier
        quota.leave();
        cpu.set(900); // new carrier with unrelated absolute CPU counter
        quota.enter();
        try {
            cpu.set(905); // total logical CPU usage = 11 ns
            var exceeded = assertThrows(
                    ActorRuntime.UntrustedActorQuotaExceededException.class,
                    quota::check);
            assertEquals(
                    ActorRuntime.UntrustedActorQuotaExceededException.Resource.CPU_TIME,
                    exceeded.resource());
        } finally {
            quota.leave();
        }
    }

    @Test
    void nestedOrConcurrentCarrierAdmissionMustNotDoubleCharge() {
        AtomicLong now = new AtomicLong();
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                100, 10, 0, 0, now::get, now::get);
        quota.enter();
        assertThrows(IllegalStateException.class, quota::enter);
        quota.leave();
        quota.enter();
        quota.leave();
        assertThrows(IllegalStateException.class, quota::leave);
    }

    @Test
    void failingCpuSamplerDoesNotStrandTheOldCarrierLease() {
        AtomicInteger samples = new AtomicInteger();
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                1000, 3, 0, 0, () -> 0, () -> {
                    if (samples.incrementAndGet() == 2) {
                        throw new IllegalStateException("mock CPU sampler failure");
                    }
                    return 100;
                });
        quota.enter();
        assertThrows(IllegalStateException.class, quota::leave);
        // The failing leave MUST clear segmentActive so future requests do not
        // inherit an active physical carrier identity.
        quota.enter();
        quota.chargeSafepoint();
        quota.leave();
    }

    @Test
    void exhaustedFuelNeverUnderflowsOrWrapsIntoASecondBudget() {
        AtomicLong clock = new AtomicLong();
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                10_000L, 0, 0, 0, clock::get, clock::get);
        quota.enter();
        try {
            for (int i = 0; i < 3; i++) {
                var failure = assertThrows(
                        ActorRuntime.UntrustedActorQuotaExceededException.class,
                        quota::chargeSafepoint);
                assertEquals(
                        ActorRuntime.UntrustedActorQuotaExceededException.Resource.FUEL,
                        failure.resource());
            }
        } finally {
            quota.leave();
        }
    }

    @Test
    void concurrentCarrierAdmissionsCannotBothEnterSameLogicalQuota() throws Exception {
        AtomicLong clock = new AtomicLong();
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                1_000_000L, 20, 0, 0, clock::get, clock::get);
        java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Throwable> overlapping = new AtomicReference<>();
        Thread first = Thread.ofPlatform().start(() -> {
            quota.enter();
            entered.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                quota.leave();
            }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Thread second = Thread.ofPlatform().start(() -> {
                try {
                    quota.enter();
                } catch (Throwable failure) {
                    overlapping.set(failure);
                }
            });
            second.join(3_000);
            assertTrue(!second.isAlive(), "second admission may not block forever");
            assertTrue(overlapping.get() instanceof IllegalStateException,
                    "a quota cannot be entered by two carrier threads simultaneously");
        } finally {
            release.countDown();
            first.join(3_000);
        }
        quota.enter();
        quota.chargeSafepoint();
        quota.leave();
    }

    @Test
    void untrustedActorCannotMintSiblingRootQuotasWhileTaskIsSuspended()
            throws Exception {
        AtomicReference<OresFuture<Integer>> retained = new AtomicReference<>();
        AtomicReference<Throwable> secondRejected = new AtomicReference<>();
        AtomicReference<Throwable> physicalBudgetDenied = new AtomicReference<>();
        java.util.concurrent.CountDownLatch attempted =
                new java.util.concurrent.CountDownLatch(1);
        OresFuture<Integer> wait = new OresFuture<>();
        siblingProbe = new SiblingProbe(retained, secondRejected, physicalBudgetDenied, attempted, wait);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) -> {
                SiblingProbe probe = siblingProbe;
                if (message.equals("first")) {
                    probe.retained().set(turn.runtime().startActorTask(resume ->
                            resume.initial() ? OresScheduler.<Integer>await(probe.wait())
                                    : OresScheduler.done(7)));
                } else if (message.equals("sibling")) {
                    try {
                        turn.runtime().startActorTask(
                                resume -> OresScheduler.done(999));
                    } catch (SecurityException expected) {
                        probe.secondRejected().set(expected);
                    } finally {
                        try {
                            turn.runtime().schedulerSafepoint();
                        } catch (SecurityException expected) {
                            probe.physicalBudgetDenied().set(expected);
                        }
                        probe.attempted().countDown();
                    }
                }
            });
            actor.send("first");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (retained.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(retained.get() != null, "first source task must be admitted");
            actor.send("sibling");
            assertTrue(attempted.await(3, TimeUnit.SECONDS));
            assertTrue(secondRejected.get() instanceof SecurityException,
                    "a second top-level source task cannot mint another quota");
            assertTrue(physicalBudgetDenied.get() instanceof SecurityException,
                    "a physical user turn cannot spend another quota while suspended");
            // First source Future is still registered while the second message
            // runs; completing it lets the quota cohort retire.
            wait.completeFromRuntime(1);
            assertEquals(7, retained.get().get(3, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            siblingProbe = null;
        }
    }

    @Test
    void nestedUntrustedTasksShareOneQuotaAndRetireWithoutUnderflow()
            throws Exception {
        AtomicReference<OresFuture<Integer>> outerRef = new AtomicReference<>();
        nestedProbe = new NestedProbe(outerRef);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) ->
                    nestedProbe.outerRef().set(turn.runtime().startActorTask(new OresScheduler.Task<>() {
                        private OresFuture<Integer> child;

                        @Override
                        public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resume) {
                            turn.runtime().schedulerSafepoint();
                            if (resume.initial()) {
                                child = turn.runtime().startActorTask(
                                        next -> {
                                            turn.runtime().schedulerSafepoint();
                                            return OresScheduler.done(7);
                                        });
                                return OresScheduler.await(child);
                            }
                            return OresScheduler.done((Integer) resume.value());
                        }
                    })));
            actor.send("nested");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (outerRef.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(outerRef.get() != null, "parent task was not admitted");
            assertEquals(7, outerRef.get().get(3, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            nestedProbe = null;
        }
    }

    @Test
    void actualUntrustedActorCannotRefuelThroughRepeatedCooperate() throws Exception {
        AtomicReference<OresFuture<Integer>> taskRef = new AtomicReference<>();
        AtomicInteger dispatches = new AtomicInteger();
        fuelProbe = new FuelProbe(taskRef, dispatches);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) ->
                    fuelProbe.taskRef().set(turn.runtime().startActorTask(resume -> {
                        fuelProbe.dispatches().incrementAndGet();
                        // The UNTRUSTED default budget is 250ms x 256 = 64,000
                        // fuel units. Six 13,000-checkpoint physical dispatches
                        // exceed that cumulative budget despite each individual
                        // dispatch remaining under it.
                        for (int i = 0; i < 13_000; i++) {
                            turn.runtime().schedulerSafepoint();
                        }
                        return OresScheduler.cooperate();
                    })));
            actor.send("start");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (taskRef.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            OresFuture<Integer> result = taskRef.get();
            assertTrue(result != null, "source task was not admitted");
            var failed = assertThrows(ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(
                    failed.getCause() instanceof ActorRuntime.UntrustedActorQuotaExceededException,
                    () -> "unexpected failed task: " + failed.getCause());
            assertEquals(
                    ActorRuntime.UntrustedActorQuotaExceededException.Resource.FUEL,
                    ((ActorRuntime.UntrustedActorQuotaExceededException) failed.getCause()).resource());
            assertTrue(dispatches.get() >= 5, "fuel must be charged across physical resumes");
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            fuelProbe = null;
        }
    }

    @Test
    void actualUntrustedActorTaskCannotResetDeadlineAfterAwait() throws Exception {
        AtomicReference<OresFuture<Integer>> taskRef = new AtomicReference<>();
        AtomicInteger resumes = new AtomicInteger();
        OresFuture<Integer> awaited = new OresFuture<>();
        deadlineProbe = new DeadlineProbe(taskRef, resumes, awaited);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) ->
                    deadlineProbe.taskRef().set(turn.runtime().startActorTask(resume -> {
                        int step = deadlineProbe.resumes().getAndIncrement();
                        turn.runtime().schedulerSafepoint();
                        if (step == 0) return OresScheduler.await(deadlineProbe.awaited());
                        return OresScheduler.done(42);
                    })));
            actor.send("start");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (taskRef.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            OresFuture<Integer> result = taskRef.get();
            assertTrue(result != null, "source task was not admitted");
            // Greater than the fixed 250ms untrusted logical wall deadline.
            Thread.sleep(300);
            awaited.completeFromRuntime(7);
            var failed = assertThrows(ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(
                    failed.getCause() instanceof ActorRuntime.UntrustedActorQuotaExceededException,
                    () -> "unexpected failed task: " + failed.getCause());
            assertEquals(
                    ActorRuntime.UntrustedActorQuotaExceededException.Resource.WALL_TIME,
                    ((ActorRuntime.UntrustedActorQuotaExceededException) failed.getCause()).resource());
            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            deadlineProbe = null;
        }
    }
}
