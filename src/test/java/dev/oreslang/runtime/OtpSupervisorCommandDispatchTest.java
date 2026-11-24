package dev.oreslang.runtime;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime turns, not merely standalone command-ledger modeling.
 * The tested callbacks are trusted-host behavior, NOT guest hot upgrades.
 */
@Timeout(30)
final class OtpSupervisorCommandDispatchTest {
    private static final String DIGEST = "a".repeat(64);

    @Test
    void priorityCommandBypassesFullDataMailboxWithoutStealingCredits() throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy oneMessage = new IsolatePolicy(
                base.capabilities(), base.maxHeapBytes(), 1,
                base.maxWallTime(), base.adversarial());
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch commandSeen = new CountDownLatch(1);
            CountDownLatch dataSeen = new CountDownLatch(1);
            List<String> order = new CopyOnWriteArrayList<>();
            var actor = runtime.<String>spawnShared(
                    oneMessage, () -> new ActorRuntime.Behavior<String>() {
                        @Override public void onMessage(
                                String message, ActorRuntime.ActorContext<String> context)
                                throws Exception {
                            if ("block".equals(message)) {
                                entered.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } else {
                                order.add("DATA");
                                dataSeen.countDown();
                            }
                        }

                        @Override public void onSupervisorCommand(
                                ActorRuntime.SupervisorCommandNotice command,
                                ActorRuntime.ActorContext<String> context) {
                            assertEquals(ActorRuntime.SupervisorOperation.PREPARE_UPGRADE,
                                    command.operation());
                            assertTrue(ActorRuntime.isActorCarrierThread());
                            assertEquals(context.self().id(),
                                    ActorRuntime.currentActorId().orElseThrow());
                            order.add("CONTROL");
                            commandSeen.countDown();
                        }

                    });
            // Host-only callbacks run on the owning serialized carrier.
            actor.send("block");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            actor.send("queued");
            assertThrows(ActorRuntime.ActorMailboxFullException.class,
                    () -> actor.send("overflow"));
            var ticket = runtime.submitSupervisorCommand(
                    actor, 1L, ActorRuntime.SupervisorOperation.PREPARE_UPGRADE, DIGEST);
            assertEquals(actor.address(), ticket.target());
            assertEquals(1, runtime.supervisorCommandStatsFor(actor).queued());
            assertEquals(1, runtime.actorDiagnostics(actor).queuedUserMessages());
            release.countDown();
            assertTrue(commandSeen.await(5, TimeUnit.SECONDS));
            assertTrue(dataSeen.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("CONTROL", "DATA"), order);
            assertEquals(0, runtime.supervisorCommandStatsFor(actor).outstanding());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void terminalChildFailurePrecedesUpgradeCommandAndOrdinaryData() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch parentEntered = new CountDownLatch(1);
            CountDownLatch releaseParent = new CountDownLatch(1);
            CountDownLatch eventsSeen = new CountDownLatch(3);
            AtomicReference<ActorRuntime.ActorRef<String>> child = new AtomicReference<>();
            List<String> order = new CopyOnWriteArrayList<>();

            var parent = runtime.<String>spawnShared(() ->
                    new ActorRuntime.Behavior<String>() {
                        @Override public void onMessage(
                                String message, ActorRuntime.ActorContext<String> context)
                                throws Exception {
                            if ("hold".equals(message)) {
                                // Actor-side spawn uses the stateless
                                // BehaviorFactory overload; trusted Supplier
                                // construction is restricted to the host.
                                child.set(context.runtime().<String>spawnShared(
                                        (ActorRuntime.BehaviorFactory<String>) factoryContext ->
                                                (m, c) -> {
                                                    throw new IllegalStateException(
                                                            "expected child failure");
                                                }));
                                parentEntered.countDown();
                                assertTrue(releaseParent.await(5, TimeUnit.SECONDS));
                            } else {
                                order.add("DATA");
                                eventsSeen.countDown();
                            }
                        }

                        @Override public void onChildFailure(
                                ActorRuntime.ActorFailureNotice notice,
                                ActorRuntime.ActorContext<String> context) {
                            assertEquals(child.get().id(), notice.actor());
                            order.add("FAILURE");
                            eventsSeen.countDown();
                        }

                        @Override public void onSupervisorCommand(
                                ActorRuntime.SupervisorCommandNotice notice,
                                ActorRuntime.ActorContext<String> context) {
                            order.add("UPGRADE");
                            eventsSeen.countDown();
                        }
                    });
            parent.send("hold");
            assertTrue(parentEntered.await(5, TimeUnit.SECONDS));
            parent.send("ordinary");
            runtime.submitSupervisorCommand(parent, 1L,
                    ActorRuntime.SupervisorOperation.QUIESCE_CHECK, DIGEST);
            child.get().send("crash");
            assertThrows(ExecutionException.class,
                    () -> child.get().done().get(5, TimeUnit.SECONDS));
            assertEquals(1, runtime.supervisionStatsFor(parent).pending());
            assertEquals(1, runtime.supervisorCommandStatsFor(parent).queued());

            releaseParent.countDown();
            assertTrue(eventsSeen.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("FAILURE", "UPGRADE", "DATA"), order);
            assertEquals(0, runtime.supervisorCommandStatsFor(parent).outstanding());
            parent.stop();
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void stoppingActorQuarantinesQueuedUpgradeWithoutInvokingItsCallback() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger observed =
                    new java.util.concurrent.atomic.AtomicInteger();
            var actor = runtime.<String>spawnShared(() ->
                    new ActorRuntime.Behavior<String>() {
                        @Override public void onMessage(
                                String msg, ActorRuntime.ActorContext<String> context)
                                throws Exception {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        }

                        @Override public void onSupervisorCommand(
                                ActorRuntime.SupervisorCommandNotice notice,
                                ActorRuntime.ActorContext<String> context) {
                            observed.incrementAndGet();
                        }
                    });
            actor.send("hold");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            runtime.submitSupervisorCommand(actor, 1,
                    ActorRuntime.SupervisorOperation.PREPARE_UPGRADE, DIGEST);
            assertEquals(1, runtime.supervisorCommandStatsFor(actor).queued());
            stopAndReleaseCallback(actor, release);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, observed.get(),
                    "retired actor must not run a quarantined upgrade command");
        }
    }

    @Test
    void stopRacingWithActiveUpgradeCommandNeverAcknowledgesQuarantine()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch commandEntered = new CountDownLatch(1);
            CountDownLatch releaseCommand = new CountDownLatch(1);
            var actor = runtime.<String>spawnShared(() ->
                    new ActorRuntime.Behavior<String>() {
                        @Override public void onMessage(
                                String msg, ActorRuntime.ActorContext<String> context) { }

                        @Override public void onSupervisorCommand(
                                ActorRuntime.SupervisorCommandNotice notice,
                                ActorRuntime.ActorContext<String> context)
                                throws Exception {
                            commandEntered.countDown();
                            assertTrue(releaseCommand.await(10, TimeUnit.SECONDS));
                        }
                    });
            runtime.submitSupervisorCommand(actor, 1,
                    ActorRuntime.SupervisorOperation.PREPARE_UPGRADE, DIGEST);
            assertTrue(commandEntered.await(10, TimeUnit.SECONDS));
            var readStats = runtime.supervisorCommandStatsReaderFor(actor);
            stopAndReleaseCallback(actor, releaseCommand);
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
            var stats = readStats.get();
            assertEquals(1, stats.quarantined());
            assertEquals(0, stats.acknowledged(),
                    "stopped actor must never ACK an uncommitted upgrade");
            assertEquals(0, stats.outstanding());
            assertEquals(0, stats.inFlight());
        }
    }

    private static void stopAndReleaseCallback(ActorRuntime.ActorRef<?> actor, CountDownLatch release)
            throws Exception {
        FutureTask<Void> stop = new FutureTask<>(() -> {
            actor.stop();
            return null;
        });
        Thread.ofPlatform().daemon().start(stop);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (actor.isAlive() && System.nanoTime() - deadline < 0) {
                LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(100));
            }
            assertFalse(actor.isAlive(), "stop must close admission before the callback returns");
        } finally {
            release.countDown();
        }
        stop.get(5, TimeUnit.SECONDS);
    }

    @Test
    void uncommittedOrStaleRouteEpochIsNotSupervisorAuthority() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() ->
                    new ActorRuntime.Behavior<String>() {
                        @Override public void onMessage(
                                String msg, ActorRuntime.ActorContext<String> context) { }
                        @Override public void onSupervisorCommand(
                                ActorRuntime.SupervisorCommandNotice notice,
                                ActorRuntime.ActorContext<String> context) { }
                    });
            for (long badEpoch : new long[] {0L, 2L, 42L, Long.MAX_VALUE}) {
                assertThrows(IllegalStateException.class,
                        () -> runtime.submitSupervisorCommand(actor, badEpoch,
                                ActorRuntime.SupervisorOperation.PREPARE_UPGRADE, DIGEST));
            }
            assertEquals(0, runtime.supervisorCommandStatsFor(actor).outstanding());
            actor.stop();
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(90)
    void racingNewCommandAgainstActorStopSettlesExactlyOnce() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 128; i++) {
                var actor = runtime.<String>spawnShared(() ->
                        new ActorRuntime.Behavior<String>() {
                            @Override public void onMessage(
                                    String value, ActorRuntime.ActorContext<String> context) { }

                            @Override public void onSupervisorCommand(
                                    ActorRuntime.SupervisorCommandNotice notice,
                                    ActorRuntime.ActorContext<String> context) { }
                        });
                actor.ready().get(5, TimeUnit.SECONDS);
                var readStats = runtime.supervisorCommandStatsReaderFor(actor);
                CountDownLatch start = new CountDownLatch(1);
                java.util.concurrent.atomic.AtomicBoolean admitted =
                        new java.util.concurrent.atomic.AtomicBoolean();
                var submitter = pool.submit(() -> {
                    start.await();
                    try {
                        runtime.submitSupervisorCommand(
                                actor, 1, ActorRuntime.SupervisorOperation.PREPARE_UPGRADE,
                                DIGEST);
                        admitted.set(true);
                    } catch (ActorRuntime.ActorTerminatedException expected) {
                        // Stop won admission; no command can be published.
                    }
                    return null;
                });
                var stopper = pool.submit(() -> {
                    start.await();
                    actor.stop();
                    return null;
                });
                start.countDown();
                submitter.get(5, TimeUnit.SECONDS);
                stopper.get(5, TimeUnit.SECONDS);
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                var snap = readStats.get();
                assertEquals(0, snap.outstanding());
                assertEquals(0, snap.queued());
                assertEquals(0, snap.inFlight());
                assertEquals(admitted.get() ? 1 : 0,
                        snap.acknowledged() + snap.quarantined(),
                        "accepted command must be handled or quarantined exactly once");
            }
        }
    }

    @Test
    void sourceActorAndActorTurnCannotMintSupervisorAuthority() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch completed = new CountDownLatch(1);
            var target = runtime.<String>spawnShared(
                    () -> new ActorRuntime.Behavior<String>() {
                        @Override public void onMessage(
                                String msg, ActorRuntime.ActorContext<String> ctx) {
                            assertThrows(SecurityException.class,
                                    () -> ctx.runtime().submitSupervisorCommand(
                                            ctx.self(), 1L,
                                            ActorRuntime.SupervisorOperation.PREPARE_UPGRADE,
                                            DIGEST));
                            completed.countDown();
                        }

                        @Override public void onSupervisorCommand(
                                ActorRuntime.SupervisorCommandNotice command,
                                ActorRuntime.ActorContext<String> ctx) { }
                    });
            target.send("attempt");
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertThrows(UnsupportedOperationException.class,
                    () -> runtime.submitSupervisorCommand(
                            target, 1L, ActorRuntime.SupervisorOperation.COMMIT_NOTICE,
                            DIGEST), "a notice is not a verified atomic publication");
            assertThrows(UnsupportedOperationException.class,
                    () -> runtime.submitSupervisorCommand(
                            target, 1L, ActorRuntime.SupervisorOperation.GRACEFUL_STOP,
                            null), "a notice is not a physical stop acknowledgment");
            assertEquals(0, runtime.supervisorCommandStatsFor(target).outstanding());
            var foreignRuntime = new ActorRuntime();
            try {
                var foreign = foreignRuntime.<String>spawnShared(() -> (msg, ctx) -> { });
                assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                        () -> runtime.submitSupervisorCommand(
                                foreign, 1L, ActorRuntime.SupervisorOperation.PREPARE_UPGRADE,
                                DIGEST));
                foreign.stop();
                assertTrue(foreign.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                foreignRuntime.close();
            }
            target.stop();
            assertTrue(target.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
