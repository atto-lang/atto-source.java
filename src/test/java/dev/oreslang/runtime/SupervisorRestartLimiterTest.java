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
