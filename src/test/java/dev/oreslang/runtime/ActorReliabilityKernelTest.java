package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class ActorReliabilityKernelTest {
    private static IsolatePolicy tinyMailboxPolicy(int messages) {
        return new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                64L * 1024 * 1024,
                messages,
                Duration.ofSeconds(5),
                false);
    }

    @Test
    void structuralHealthSnapshotSeesSaturationWithoutGuestPayload() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IsolatePolicy policy = tinyMailboxPolicy(2);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(policy, () -> (message, context) -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test handler release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            });

            actor.send("SECRET-FIRST");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            actor.send("SECRET-QUEUED-A");
            actor.send("SECRET-QUEUED-B");
            assertThrows(ActorRuntime.ActorMailboxFullException.class,
                    () -> actor.send("SECRET-REJECTED"));

            ActorRuntime.ActorHealthSnapshot health =
                    runtime.actorHealth(actor).orElseThrow();
            assertEquals(actor.id(), health.actorId());
            assertEquals(actor.generation(), health.generation());
            assertEquals(ActorRuntime.ActorKind.SHARED, health.kind());
            assertTrue(health.alive());
            assertTrue(health.initialized());
            assertEquals(2, health.queuedUserMessages());
            assertEquals(0, health.queuedContinuations());
            assertEquals(3L, health.acceptedUserMessages());
            assertEquals(0L, health.processedUserMessages());
            assertEquals(0L, health.contiguousProcessedUserMessages());
            assertEquals(0, health.outOfOrderCompletedMessages());
            assertEquals(3L, health.outstandingAcceptedMessages());
            assertEquals(0, health.pendingDrainWaiters());
            assertEquals(1, health.activeTurns());
            assertTrue(health.carrierActive());
            assertEquals(ActorRuntime.ActorHealthPhase.RUNNING, health.phase());
            assertEquals(ActorRuntime.ActorTerminalClass.NONE, health.terminalClass());
            assertEquals(1L, health.mailboxRejections());
            assertFalse(health.failed());
            assertTrue(health.parentId().isEmpty());

            String rendered = health.toString();
            assertFalse(rendered.contains("SECRET-FIRST"));
            assertFalse(rendered.contains("SECRET-QUEUED-A"));
            assertFalse(rendered.contains("SECRET-QUEUED-B"));
            assertFalse(rendered.contains("SECRET-REJECTED"));

            release.countDown();
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void mailboxOverloadLoggingIsExponentiallySampledButCounterIsExact() throws Exception {
        CoreLog.configure(CoreLog.Level.OFF);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CoreLog.configure(CoreLog.Level.WARN);
            try (ActorRuntime runtime = new ActorRuntime()) {
                var actor = runtime.<String>spawnShared(
                        tinyMailboxPolicy(1),
                        () -> (message, context) -> {
                            entered.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                        });
                actor.send("active");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                actor.send("queued");

                for (int i = 0; i < 100; i++) {
                    assertThrows(ActorRuntime.ActorMailboxFullException.class,
                            () -> actor.send("rejected"));
                }

                assertEquals(100L,
                        runtime.actorHealth(actor).orElseThrow().mailboxRejections());

                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                CoreLog.dumpRetained(
                        new PrintStream(bytes, true, StandardCharsets.UTF_8));
                long samples = bytes.toString(StandardCharsets.UTF_8).lines()
                        .filter(line -> line.contains(
                                "\"event\":\"actor.mailbox.rejected\""))
                        .count();
                assertEquals(7L, samples,
                        "100 rejections should log counts 1,2,4,8,16,32,64 only");

                release.countDown();
                actor.stop();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            }
        } finally {
            release.countDown();
            CoreLog.configure(CoreLog.Level.OFF);
        }
    }

    @Test
    void actorCannotReadHostHealthSnapshotsEvenForItself() throws Exception {
        AtomicReference<Throwable> observed = new AtomicReference<>();
        CountDownLatch checked = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnSharedTrusted(context -> (message, turn) -> {
                try {
                    turn.runtime().actorHealth(turn.self());
                    observed.set(new AssertionError(
                            "actor unexpectedly acquired host health authority"));
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    checked.countDown();
                    turn.self().stop();
                }
            });

            actor.send("inspect");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, observed.get());
            assertTrue(observed.get().getMessage().contains("host/supervisor"));
        }
    }

    @Test
    void overloadAndFailureTelemetryNeverRecordsGuestPayloadOrFailureText() throws Exception {
        CoreLog.configure(CoreLog.Level.OFF);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CoreLog.configure(CoreLog.Level.WARN);
            try (ActorRuntime runtime = new ActorRuntime()) {
                var saturated = runtime.<String>spawnShared(
                        tinyMailboxPolicy(1),
                        () -> (message, context) -> {
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException(
                                            "SECRET-SATURATION-TIMEOUT");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                        });

                saturated.send("SECRET-ACTIVE-PAYLOAD");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                saturated.send("SECRET-QUEUED-PAYLOAD");
                assertThrows(ActorRuntime.ActorMailboxFullException.class,
                        () -> saturated.send("SECRET-REJECTED-PAYLOAD"));

                var failing = runtime.<String>spawnShared(() -> (message, context) -> {
                    throw new IllegalStateException(
                            "SECRET-TERMINAL-FAILURE-TEXT");
                });
                failing.send("SECRET-FAILURE-PAYLOAD");
                assertTrue(failing.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(failing.failure().isPresent());

                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                CoreLog.dumpRetained(
                        new PrintStream(bytes, true, StandardCharsets.UTF_8));
                String log = bytes.toString(StandardCharsets.UTF_8);
                assertTrue(log.contains(
                        "\"event\":\"actor.mailbox.rejected\""));
                assertTrue(log.contains(
                        "\"event\":\"actor.terminal.failure\""));
                assertFalse(log.contains("SECRET-ACTIVE-PAYLOAD"));
                assertFalse(log.contains("SECRET-QUEUED-PAYLOAD"));
                assertFalse(log.contains("SECRET-REJECTED-PAYLOAD"));
                assertFalse(log.contains("SECRET-FAILURE-PAYLOAD"));
                assertFalse(log.contains("SECRET-TERMINAL-FAILURE-TEXT"));
                assertFalse(log.contains("IllegalStateException"));

                release.countDown();
                saturated.stop();
                assertTrue(saturated.awaitTermination(5, TimeUnit.SECONDS));
            }
        } finally {
            release.countDown();
            CoreLog.configure(CoreLog.Level.OFF);
        }
    }

    @Test
    void racingCancellationProducesOneAuthoritativeTerminalLog() throws Exception {
        CoreLog.configure(CoreLog.Level.OFF);
        try {
            CoreLog.configure(CoreLog.Level.INFO);
            try (ActorRuntime runtime = new ActorRuntime()) {
                var actor = runtime.<String>spawnShared(
                        () -> (message, context) -> { });
                actor.ready().get(5, TimeUnit.SECONDS);

                java.util.concurrent.CountDownLatch start =
                        new java.util.concurrent.CountDownLatch(1);
                java.util.ArrayList<java.util.concurrent.Future<?>> tasks =
                        new java.util.ArrayList<>();
                try (var pool = java.util.concurrent.Executors.newFixedThreadPool(16)) {
                    for (int i = 0; i < 64; i++) {
                        tasks.add(pool.submit(() -> {
                            start.await();
                            actor.cancel();
                            return null;
                        }));
                    }
                    start.countDown();
                    for (var task : tasks) task.get(5, TimeUnit.SECONDS);
                }
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));

                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                CoreLog.dumpRetained(
                        new PrintStream(bytes, true, StandardCharsets.UTF_8));
                long terminalLogs = bytes.toString(StandardCharsets.UTF_8).lines()
                        .filter(line -> line.contains(
                                "\"event\":\"actor.cancelled\""))
                        .count();
                assertEquals(1L, terminalLogs,
                        "one actor incarnation must have one winning terminal cause");
            }
        } finally {
            CoreLog.configure(CoreLog.Level.OFF);
        }
    }

    @Test
    void activeFailureUpgradesEarlierCancellationAndIsNotMasked() throws Exception {
        CoreLog.configure(CoreLog.Level.OFF);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CoreLog.configure(CoreLog.Level.INFO);
            try (ActorRuntime runtime = new ActorRuntime()) {
                var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test release timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    throw new IllegalStateException("secret-active-failure");
                });

                actor.send("run");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(actor.cancel(), "cancellation must seal the actor first");
                release.countDown();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));

                ActorRuntime.ActorHealthSnapshot health =
                        runtime.actorHealth(actor).orElseThrow();
                assertEquals(ActorRuntime.ActorTerminalClass.FAILURE,
                        health.terminalClass());
                assertTrue(health.failed());

                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                CoreLog.dumpRetained(
                        new PrintStream(bytes, true, StandardCharsets.UTF_8));
                String logs = bytes.toString(StandardCharsets.UTF_8);
                long failures = logs.lines()
                        .filter(line -> line.contains(
                                "\"event\":\"actor.terminal.failure\""))
                        .count();
                long cancellations = logs.lines()
                        .filter(line -> line.contains(
                                "\"event\":\"actor.cancelled\""))
                        .count();
                assertEquals(1L, failures);
                assertEquals(0L, cancellations);
                assertFalse(logs.contains("secret-active-failure"));
            }
        } finally {
            release.countDown();
            CoreLog.configure(CoreLog.Level.OFF);
        }
    }

    @Test
    void terminalHealthSurvivesCellRetirementWithoutRetainingFailureText() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var failing = runtime.<String>spawnShared(() -> (message, context) -> {
                throw new IllegalStateException("SECRET-POSTMORTEM-FAILURE");
            });
            failing.send("SECRET-POSTMORTEM-PAYLOAD");
            assertTrue(failing.awaitTermination(5, TimeUnit.SECONDS));

            ActorRuntime.ActorHealthSnapshot failed =
                    runtime.actorHealth(failing).orElseThrow();
            assertEquals(ActorRuntime.ActorHealthPhase.TERMINATED, failed.phase());
            assertEquals(ActorRuntime.ActorTerminalClass.FAILURE,
                    failed.terminalClass());
            assertTrue(failed.failed());
            assertFalse(failed.alive());
            assertTrue(failed.finalized());
            assertEquals(0, failed.queuedUserMessages());
            assertEquals(0, failed.queuedContinuations());
            assertFalse(failed.toString().contains("SECRET-POSTMORTEM-FAILURE"));
            assertFalse(failed.toString().contains("SECRET-POSTMORTEM-PAYLOAD"));

            var cancelled = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            cancelled.ready().get(5, TimeUnit.SECONDS);
            assertTrue(cancelled.cancel());
            assertTrue(cancelled.awaitTermination(5, TimeUnit.SECONDS));
            ActorRuntime.ActorHealthSnapshot cancelledHealth =
                    runtime.actorHealth(cancelled).orElseThrow();
            assertEquals(ActorRuntime.ActorTerminalClass.CANCELLED,
                    cancelledHealth.terminalClass());
            assertFalse(cancelledHealth.failed());

            var graceful = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            graceful.ready().get(5, TimeUnit.SECONDS);
            graceful.stop();
            assertTrue(graceful.awaitTermination(5, TimeUnit.SECONDS));
            ActorRuntime.ActorHealthSnapshot gracefulHealth =
                    runtime.actorHealth(graceful).orElseThrow();
            assertEquals(ActorRuntime.ActorTerminalClass.NONE,
                    gracefulHealth.terminalClass());
            assertFalse(gracefulHealth.failed());
        }
    }

    @Test
    void runtimeHealthIsAggregateBoundedAndHostOnly() throws Exception {
        AtomicReference<Throwable> actorAttempt = new AtomicReference<>();
        CountDownLatch attempted = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnSharedTrusted(context -> (message, turn) -> {
                try {
                    turn.runtime().runtimeHealth();
                    actorAttempt.set(new AssertionError(
                            "actor unexpectedly read runtime health"));
                } catch (Throwable failure) {
                    actorAttempt.set(failure);
                } finally {
                    attempted.countDown();
                    turn.self().stop();
                }
            });

            ActorRuntime.RuntimeHealthSnapshot before = runtime.runtimeHealth();
            assertEquals(1, before.actorCount());
            assertTrue(before.privateMemoryBytes() >= 0);
            assertTrue(before.sharedMemoryBytes() >= 0);
            assertTrue(before.watchdogOverruns() >= 0);
            assertTrue(before.watchdogNoticesDropped() >= 0);
            assertTrue(before.watchdogObserverFailures() >= 0);
            assertFalse(before.closed());

            actor.send("try");
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, actorAttempt.get());

            ActorRuntime.RuntimeHealthSnapshot after = runtime.runtimeHealth();
            assertEquals(0, after.actorCount());
        }
    }

    @Test
    void foreignRuntimeCannotInspectActorHealth() {
        try (ActorRuntime left = new ActorRuntime();
             ActorRuntime right = new ActorRuntime()) {
            var actor = left.<String>spawnShared(() -> (message, context) -> { });
            assertThrows(IllegalArgumentException.class,
                    () -> right.actorHealth(actor));
            actor.stop();
        }
    }
}
