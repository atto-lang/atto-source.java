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
    void actualUntrustedActorCannotRefuelThroughRepeatedCooperate() throws Exception {
        AtomicReference<OresFuture<Integer>> taskRef = new AtomicReference<>();
        AtomicInteger dispatches = new AtomicInteger();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) ->
                    taskRef.set(turn.runtime().startActorTask(resume -> {
                        dispatches.incrementAndGet();
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
        }
    }

    @Test
    void actualUntrustedActorTaskCannotResetDeadlineAfterAwait() throws Exception {
        AtomicReference<OresFuture<Integer>> taskRef = new AtomicReference<>();
        AtomicInteger resumes = new AtomicInteger();
        OresFuture<Integer> awaited = new OresFuture<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) ->
                    taskRef.set(turn.runtime().startActorTask(resume -> {
                        int step = resumes.getAndIncrement();
                        turn.runtime().schedulerSafepoint();
                        if (step == 0) return OresScheduler.await(awaited);
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
        }
    }
}
