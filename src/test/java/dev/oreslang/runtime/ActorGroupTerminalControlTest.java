package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class ActorGroupTerminalControlTest {
    private static IsolatePolicy oneWorkerPolicy() {
        return new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                64L * 1024 * 1024,
                1,
                Duration.ofSeconds(5),
                false);
    }

    @Test
    void unackedTerminalReceiptBackpressuresNewWorkerAdmission() throws Exception {
        IsolatePolicy policy = oneWorkerPolicy();
        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            var worker = group.<String>spawnWorker(
                    ActorRuntime.ActorKind.SHARED,
                    policy,
                    factory -> (message, turn) -> {
                        throw new IllegalStateException("secret-worker-failure");
                    });

            worker.send("fail");
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));

            var stats = group.terminalControlStats();
            assertEquals(1, stats.capacity());
            assertEquals(1, stats.inUse());
            assertEquals(1, stats.ready());

            var receipt = group.peekTerminalReceipt().orElseThrow();
            assertEquals(worker.id(), receipt.actorId());
            assertEquals(worker.generation(), receipt.incarnation());
            assertEquals(SupervisorTerminalLedger.Kind.FAILURE, receipt.kind());
            assertFalse(receipt.toString().contains("secret-worker-failure"));

            IllegalStateException saturated = assertThrows(
                    IllegalStateException.class,
                    () -> group.spawnWorker(
                            ActorRuntime.ActorKind.SHARED,
                            policy,
                            factory -> (message, turn) -> { }));
            assertTrue(saturated.getMessage().contains("CONTROL capacity exhausted"));
            assertEquals(1L, group.terminalControlStats().reservationRejections());

            group.acknowledgeTerminalReceipt(receipt);
            assertEquals(0, group.terminalControlStats().inUse());

            var replacement = group.<String>spawnWorker(
                    ActorRuntime.ActorKind.SHARED,
                    policy,
                    factory -> (message, turn) -> { });
            replacement.ready().get(5, TimeUnit.SECONDS);
            replacement.stop();
            assertTrue(replacement.awaitTermination(5, TimeUnit.SECONDS));
            var graceful = group.peekTerminalReceipt().orElseThrow();
            assertEquals(SupervisorTerminalLedger.Kind.GRACEFUL, graceful.kind());
            group.acknowledgeTerminalReceipt(graceful);
        }
    }

    @Test
    void startupFailureHasDistinctTerminalKind() throws Exception {
        IsolatePolicy policy = oneWorkerPolicy();
        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            var worker = group.<String>spawnWorker(
                    ActorRuntime.ActorKind.SHARED,
                    policy,
                    factory -> {
                        throw new IllegalStateException("secret-startup-failure");
                    });

            assertThrows(ExecutionException.class,
                    () -> worker.ready().get(5, TimeUnit.SECONDS));
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));

            var receipt = group.peekTerminalReceipt().orElseThrow();
            assertEquals(SupervisorTerminalLedger.Kind.STARTUP_FAILED, receipt.kind());
            assertFalse(receipt.toString().contains("secret-startup-failure"));
            group.acknowledgeTerminalReceipt(receipt);
        }
    }

    @Test
    void groupCloseKeepsCancellationReceiptReplayable() throws Exception {
        IsolatePolicy policy = oneWorkerPolicy();
        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            var worker = group.<String>spawnWorker(
                    ActorRuntime.ActorKind.SHARED,
                    policy,
                    factory -> (message, turn) -> { });
            worker.ready().get(5, TimeUnit.SECONDS);

            group.close();
            assertTrue(group.closed());
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));

            var first = group.peekTerminalReceipt().orElseThrow();
            var replay = group.peekTerminalReceipt().orElseThrow();
            assertSame(first, replay);
            assertEquals(SupervisorTerminalLedger.Kind.CANCELLED, first.kind());
            assertEquals(1, group.terminalControlStats().ready());
            group.acknowledgeTerminalReceipt(first);
            assertEquals(0, group.terminalControlStats().ready());
        }
    }

    @Test
    void transferReleasesOldReceiptCreditAndOnlyNewOwnerGetsTerminal() throws Exception {
        IsolatePolicy policy = oneWorkerPolicy();
        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            ActorRuntime.ActorGroup first = runtime.createActorGroup();
            ActorRuntime.ActorGroup second = runtime.createActorGroup();
            var worker = first.<String>spawnWorker(
                    ActorRuntime.ActorKind.SHARED,
                    policy,
                    factory -> (message, turn) -> { });

            assertEquals(1, first.terminalControlStats().inUse());
            assertTrue(first.releaseWorker(worker));
            assertEquals(0, first.terminalControlStats().inUse());

            second.manageWorker(worker);
            worker.ready().get(5, TimeUnit.SECONDS);
            worker.stop();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));

            assertTrue(first.peekTerminalReceipt().isEmpty());
            var receipt = second.peekTerminalReceipt().orElseThrow();
            assertEquals(SupervisorTerminalLedger.Kind.GRACEFUL, receipt.kind());
            second.acknowledgeTerminalReceipt(receipt);
        }
    }

    @Test
    void actorCodeCannotInspectOrAcknowledgeSupervisorTerminalControl() throws Exception {
        IsolatePolicy policy = oneWorkerPolicy();
        AtomicReference<Throwable> observed = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            var worker = group.<String>spawnWorker(
                    ActorRuntime.ActorKind.SHARED,
                    policy,
                    factory -> (message, turn) -> {
                        try {
                            group.peekTerminalReceipt();
                            observed.set(new AssertionError(
                                    "actor read supervisor terminal CONTROL"));
                        } catch (Throwable failure) {
                            observed.set(failure);
                        }
                        turn.self().stop();
                    });

            worker.send("inspect");
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, observed.get());
            assertTrue(group.terminalControlHealthy());
            var receipt = group.peekTerminalReceipt().orElseThrow();
            assertEquals(SupervisorTerminalLedger.Kind.GRACEFUL, receipt.kind());
            group.acknowledgeTerminalReceipt(receipt);
        }
    }
}
