package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class SupervisorRestartLimiterTest {

    private static ActorRuntime.SupervisorControlEvent event(
            ActorRuntime.ActorId id,
            long generation) {
        return new ActorRuntime.SupervisorControlEvent(
                id,
                generation,
                ActorRuntime.ActorKind.SHARED,
                Optional.empty(),
                ActorFailure.Kind.PANIC,
                "panic");
    }

    @Test
    void boundsRestartIntensityAndBackoffDeterministically() {
        AtomicLong clock = new AtomicLong();
        SupervisorRestartLimiter limiter = new SupervisorRestartLimiter(
                new SupervisorRestartPolicy(
                        3,
                        Duration.ofSeconds(10),
                        Duration.ofMillis(100),
                        Duration.ofMillis(800)),
                clock::get,
                SupervisorRestartLimiter.Jitter.none());

        ActorRuntime.ActorId id = ActorRuntime.ActorId.create();

        SupervisorRestartLimiter.Decision first = limiter.decide(event(id, 1));
        assertEquals(SupervisorRestartLimiter.Action.RESTART, first.action());
        assertEquals(Duration.ofMillis(100), first.backoff());
        assertEquals(1, first.restartsInWindow());

        SupervisorRestartLimiter.Decision replay = limiter.decide(event(id, 1));
        assertEquals(first.action(), replay.action());
        assertEquals(first.backoff(), replay.backoff());
        assertEquals(1, replay.restartsInWindow());
        assertTrue(replay.repeatedObservation());

        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
        assertEquals(
                Duration.ofMillis(200),
                limiter.decide(event(id, 2)).backoff());
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
        assertEquals(
                Duration.ofMillis(400),
                limiter.decide(event(id, 3)).backoff());

        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
        SupervisorRestartLimiter.Decision exhausted =
                limiter.decide(event(id, 4));
        assertEquals(
                SupervisorRestartLimiter.Action.ESCALATE,
                exhausted.action());
        assertEquals(Duration.ZERO, exhausted.backoff());
        assertEquals(3, exhausted.restartsInWindow());

        SupervisorRestartLimiter.Decision exhaustedReplay =
                limiter.decide(event(id, 4));
        assertTrue(exhaustedReplay.repeatedObservation());
        assertEquals(3, exhaustedReplay.restartsInWindow());
    }

    @Test
    void restartWindowPrunesOldAttempts() {
        AtomicLong clock = new AtomicLong();
        SupervisorRestartLimiter limiter = new SupervisorRestartLimiter(
                new SupervisorRestartPolicy(
                        2,
                        Duration.ofSeconds(5),
                        Duration.ofMillis(25),
                        Duration.ofMillis(100)),
                clock::get,
                SupervisorRestartLimiter.Jitter.none());
        ActorRuntime.ActorId id = ActorRuntime.ActorId.create();

        assertEquals(
                Duration.ofMillis(25),
                limiter.decide(event(id, 1)).backoff());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(6));
        assertEquals(
                Duration.ofMillis(25),
                limiter.decide(event(id, 2)).backoff());
        assertEquals(1, limiter.snapshot(id).restartsInWindow());
    }

    @Test
    void injectedJitterIsReplayableAndClamped() {
        AtomicLong clock = new AtomicLong();
        SupervisorRestartLimiter limiter = new SupervisorRestartLimiter(
                new SupervisorRestartPolicy(
                        5,
                        Duration.ofSeconds(30),
                        Duration.ofMillis(100),
                        Duration.ofMillis(250)),
                clock::get,
                (id, generation, ordinal, base) ->
                        ordinal == 1
                                ? TimeUnit.MILLISECONDS.toNanos(25)
                                : TimeUnit.SECONDS.toNanos(10));

        ActorRuntime.ActorId id = ActorRuntime.ActorId.create();
        assertEquals(
                Duration.ofMillis(125),
                limiter.decide(event(id, 1)).backoff());
        clock.incrementAndGet();
        assertEquals(
                Duration.ofMillis(250),
                limiter.decide(event(id, 2)).backoff(),
                "jitter must never exceed configured maxBackoff");
    }

    @Test
    void stableGenerationCanResetCrashLoopButStaleGenerationCannot() {
        AtomicLong clock = new AtomicLong();
        SupervisorRestartLimiter limiter = new SupervisorRestartLimiter(
                new SupervisorRestartPolicy(
                        1,
                        Duration.ofSeconds(60),
                        Duration.ofMillis(10),
                        Duration.ofMillis(10)),
                clock::get,
                SupervisorRestartLimiter.Jitter.none());
        ActorRuntime.ActorId id = ActorRuntime.ActorId.create();

        assertEquals(
                SupervisorRestartLimiter.Action.RESTART,
                limiter.decide(event(id, 1)).action());
        clock.incrementAndGet();
        assertEquals(
                SupervisorRestartLimiter.Action.ESCALATE,
                limiter.decide(event(id, 2)).action());

        assertThrows(
                IllegalArgumentException.class,
                () -> limiter.resetAfterStability(id, 1));

        limiter.resetAfterStability(id, 3);
        SupervisorRestartLimiter.Snapshot reset = limiter.snapshot(id);
        assertEquals(0, reset.restartsInWindow());
        assertFalse(reset.escalated());

        clock.incrementAndGet();
        SupervisorRestartLimiter.Decision afterStable =
                limiter.decide(event(id, 3));
        assertEquals(
                SupervisorRestartLimiter.Action.RESTART,
                afterStable.action());
        assertEquals(Duration.ofMillis(10), afterStable.backoff());
    }

    @Test
    void staleFailureCannotConsumeOrResetNewerBudget() {
        AtomicLong clock = new AtomicLong();
        SupervisorRestartLimiter limiter = new SupervisorRestartLimiter(
                new SupervisorRestartPolicy(
                        2,
                        Duration.ofSeconds(60),
                        Duration.ZERO,
                        Duration.ZERO),
                clock::get,
                SupervisorRestartLimiter.Jitter.none());
        ActorRuntime.ActorId id = ActorRuntime.ActorId.create();

        limiter.decide(event(id, 5));
        assertThrows(
                IllegalArgumentException.class,
                () -> limiter.decide(event(id, 4)));
        assertThrows(
                IllegalArgumentException.class,
                () -> limiter.resetAfterStability(id, 4));
        assertEquals(1, limiter.snapshot(id).restartsInWindow());
    }

    @Test
    void restartHistoryCapacityFailsClosedWithoutEviction() {
        AtomicLong clock = new AtomicLong();
        SupervisorRestartLimiter limiter = new SupervisorRestartLimiter(
                new SupervisorRestartPolicy(
                        2,
                        Duration.ofSeconds(60),
                        Duration.ZERO,
                        Duration.ZERO),
                clock::get,
                SupervisorRestartLimiter.Jitter.none(),
                1);

        ActorRuntime.ActorId first = ActorRuntime.ActorId.create();
        ActorRuntime.ActorId second = ActorRuntime.ActorId.create();
        assertEquals(
                SupervisorRestartLimiter.Action.RESTART,
                limiter.decide(event(first, 1)).action());
        assertEquals(1, limiter.trackedActors());

        SupervisorRestartLimiter.Decision denied =
                limiter.decide(event(second, 1));
        assertEquals(
                SupervisorRestartLimiter.Action.ESCALATE,
                denied.action());
        assertEquals(1, limiter.trackedActors(),
                "capacity exhaustion must not evict active restart history");

        assertTrue(limiter.forget(first, 1));
        assertEquals(0, limiter.trackedActors());
        assertEquals(
                SupervisorRestartLimiter.Action.RESTART,
                limiter.decide(event(second, 1)).action());
    }

    @Test
    void stabilityResetMustAdvancePastLastFailureGeneration() {
        SupervisorRestartLimiter limiter = new SupervisorRestartLimiter(
                new SupervisorRestartPolicy(
                        2,
                        Duration.ofSeconds(60),
                        Duration.ZERO,
                        Duration.ZERO),
                () -> 0L,
                SupervisorRestartLimiter.Jitter.none());
        ActorRuntime.ActorId id = ActorRuntime.ActorId.create();
        limiter.decide(event(id, 7));

        assertThrows(
                IllegalArgumentException.class,
                () -> limiter.resetAfterStability(id, 7));
        limiter.resetAfterStability(id, 8);
        assertEquals(0, limiter.snapshot(id).restartsInWindow());
    }

    @Test
    void staleForgetCannotEraseNewerRestartHistory() {
        SupervisorRestartLimiter limiter = new SupervisorRestartLimiter(
                new SupervisorRestartPolicy(
                        2,
                        Duration.ofSeconds(60),
                        Duration.ZERO,
                        Duration.ZERO),
                () -> 0L,
                SupervisorRestartLimiter.Jitter.none());
        ActorRuntime.ActorId id = ActorRuntime.ActorId.create();
        limiter.decide(event(id, 9));
        assertThrows(
                IllegalArgumentException.class,
                () -> limiter.forget(id, 8));
        assertEquals(1, limiter.trackedActors());
        assertTrue(limiter.forget(id, 9));
        assertEquals(0, limiter.trackedActors());
    }

    @Test
    void zeroRestartPolicyEscalatesImmediately() {
        SupervisorRestartLimiter limiter = new SupervisorRestartLimiter(
                new SupervisorRestartPolicy(
                        0,
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        Duration.ZERO),
                () -> 0L,
                SupervisorRestartLimiter.Jitter.none());
        SupervisorRestartLimiter.Decision decision =
                limiter.decide(event(ActorRuntime.ActorId.create(), 1));
        assertEquals(
                SupervisorRestartLimiter.Action.ESCALATE,
                decision.action());
        assertEquals(0, decision.restartsInWindow());
    }

    @Test
    void runtimePolicyEvaluationRequiresAuthoritativeRetainedControl() throws Exception {
        AtomicLong clock = new AtomicLong();
        SupervisorRestartLimiter limiter = new SupervisorRestartLimiter(
                new SupervisorRestartPolicy(
                        2,
                        Duration.ofSeconds(60),
                        Duration.ZERO,
                        Duration.ZERO),
                clock::get,
                SupervisorRestartLimiter.Jitter.none());

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> failed = runtime.spawnShared(
                    () -> (message, context) -> {
                        throw new IllegalStateException("authoritative-only");
                    });
            failed.send("fail");
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> failed.getDone().get(5, TimeUnit.SECONDS));

            assertTrue(runtime.evaluateSupervisorRestartPolicy(
                            limiter,
                            failed.id(),
                            failed.generation() + 1)
                    .isEmpty());
            assertEquals(0, limiter.trackedActors(),
                    "fabricated generation must not consume restart history");

            SupervisorRestartLimiter.Decision decision =
                    runtime.evaluateSupervisorRestartPolicy(
                                    limiter,
                                    failed.id(),
                                    failed.generation())
                            .orElseThrow();
            assertEquals(
                    SupervisorRestartLimiter.Action.RESTART,
                    decision.action());
            assertEquals(1, limiter.trackedActors());

            SupervisorRestartLimiter.Decision replay =
                    runtime.evaluateSupervisorRestartPolicy(
                                    limiter,
                                    failed.id(),
                                    failed.generation())
                            .orElseThrow();
            assertTrue(replay.repeatedObservation());
            assertEquals(1, replay.restartsInWindow());

            ActorRuntime.SupervisorRestartPermit permit = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (permit == null && System.nanoTime() < deadline) {
                permit = runtime.tryAcquireSupervisorRestartPermit(
                                failed.id(), failed.generation())
                        .orElse(null);
                if (permit == null) Thread.sleep(1);
            }
            assertNotNull(permit);
            assertTrue(runtime.evaluateSupervisorRestartPolicy(
                            limiter,
                            failed.id(),
                            failed.generation())
                    .isEmpty(),
                    "consumed CONTROL must no longer be policy-evaluable");

            ActorRuntime.ActorRef<String> replacement =
                    runtime.restartAfterFailure(permit);
            replacement.getReady().get(3, TimeUnit.SECONDS);
            runtime.resetSupervisorRestartBudgetAfterStability(
                    limiter, replacement);
            assertEquals(0, limiter.snapshot(replacement.id()).restartsInWindow());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.resetSupervisorRestartBudgetAfterStability(
                            limiter, failed),
                    "stale ref must not reset replacement restart budget");

            replacement.stop();
            replacement.getDone().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void backwardsClockFailsClosed() {
        AtomicLong clock = new AtomicLong(10);
        SupervisorRestartLimiter limiter = new SupervisorRestartLimiter(
                new SupervisorRestartPolicy(
                        1,
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        Duration.ZERO),
                clock::get,
                SupervisorRestartLimiter.Jitter.none());
        limiter.snapshot(ActorRuntime.ActorId.create());
        clock.set(9);
        assertThrows(
                IllegalStateException.class,
                () -> limiter.snapshot(ActorRuntime.ActorId.create()));
    }
}
