package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real actor/runtime integration coverage (not just a model mailbox).
 * These source actors execute on SHARED Java/Graal contexts; this does not
 * prove per-actor protected address spaces or LLVM image loading.
 */
@Timeout(60)
final class LogicalSourceActorProxyBridgeV1Test {
    private static final Duration WAIT = Duration.ofSeconds(5);

    private static final String V1 = """
            define actor Counter as
              let int count;
              on_start(): void { self.count = 1; return; }
              receive(ActorMail<int> mail): void {
                self.count = self.count + mail.value;
                self.send(self.count);
                return;
              }
            end
            pub routine main(): void { return; }
            """;

    private static final String V2 = """
            define actor Counter as
              let int count;
              on_start(): void { self.count = 999; return; }
              receive(ActorMail<int> mail): void {
                if mail.value < 0; do
                  self.count = self.count - mail.value;
                else
                  self.count = self.count + mail.value;
                fi
                self.send(self.count);
                return;
              }
            end
            pub routine main(): void { return; }
            """;

    private static long next(LogicalSourceActorProxyBridgeV1 bridge)
            throws Exception {
        var step = bridge.nextOutput(WAIT);
        assertFalse(step.done(), "actor output must not terminate");
        return assertInstanceOf(Long.class, step.value().value());
    }

    @Test
    void compiledActorDrainReceiptRejectsForgedEnvelopeStaleEpochAndReplay()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("receipt-v1.ores", V1);
            try (var router = LogicalSourceActorRouterV1.spawn(
                         first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var proxy = new LogicalActorProxyMailboxV1(2, 8, 16)) {
                byte[] wire = new byte[Long.BYTES];
                wire[Long.BYTES - 1] = 41;
                var admitted = proxy.admit(wire);
                var dispatched = proxy.pollDispatch();
                assertSame(admitted, dispatched);

                // The wrong epoch is rejected BEFORE physical send. Credits
                // remain assigned to this outstanding logical delivery.
                assertThrows(SecurityException.class,
                        () -> router.sendAndDrainForProxy(
                                proxy, dispatched, proxy.snapshot().routeEpoch() + 1));
                assertEquals(1, proxy.snapshot().outstandingMessages());

                try (var foreignMailbox = new LogicalActorProxyMailboxV1(2, 8, 16)) {
                    byte[] copy = dispatched.payload();
                    foreignMailbox.admit(copy);
                    foreignMailbox.pollDispatch();
                    assertThrows(SecurityException.class,
                            () -> router.sendAndDrainForProxy(
                                    foreignMailbox, dispatched,
                                    foreignMailbox.snapshot().routeEpoch()),
                            "same sequence and epoch cannot borrow another mailbox's envelope");
                }

                var receipt = router.sendAndDrainForProxy(
                        proxy, dispatched, proxy.snapshot().routeEpoch());
                assertThrows(IllegalStateException.class,
                        () -> router.sendAndDrainForProxy(
                                proxy, dispatched, proxy.snapshot().routeEpoch()),
                        "one accepted physical send cannot be submitted twice "
                                + "before its drain acknowledgment");
                assertEquals(1, proxy.snapshot().retainedMessages());
                var forged = new LogicalActorProxyMailboxV1.Envelope(
                        dispatched.messageSequence(), dispatched.payload());
                assertThrows(SecurityException.class,
                        () -> receipt.awaitAndComplete(forged, WAIT),
                        "equal bytes and sequence are not a physical receipt");
                assertEquals(1, proxy.snapshot().retainedMessages());
                assertEquals(0L, proxy.snapshot().completionWatermark());

                receipt.awaitAndComplete(dispatched, WAIT);
                assertEquals(0, proxy.snapshot().retainedMessages());
                assertEquals(1L, proxy.snapshot().completionWatermark());
                assertThrows(IllegalStateException.class,
                        () -> receipt.awaitAndComplete(dispatched, WAIT),
                        "a cumulative physical drain cannot refund twice");
                assertEquals(0, proxy.snapshot().retainedMessages());
                var output = router.ref().nextOutput(WAIT);
                assertFalse(output.done());
                long observed = assertInstanceOf(
                        Long.class, output.value().value());
                assertEquals(42L, observed);
            }
        }
    }

    @Test
    void realCompiledSharedActorUpgradeWaitsForSuspendedPinnedTask()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit());
             OresScheduler scheduler = new OresScheduler(1)) {
            var first = manager.stageActorGeneration("task-pinned-v1.ores", V1);
            var candidate = manager.stageActorGeneration("task-pinned-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 4, 8, 32)) {
                var identity = bridge.address();
                bridge.admitLong(41L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));

                var release = new OresFuture<Integer>();
                var suspended = new java.util.concurrent.CountDownLatch(1);
                var turns = new java.util.concurrent.atomic.AtomicInteger();
                var task = bridge.startHostPinnedTaskForUpgrade(
                        scheduler, resume -> {
                            turns.incrementAndGet();
                            if (resume.initial()) {
                                suspended.countDown();
                                return OresScheduler.await(release);
                            }
                            assertNull(resume.failure());
                            return OresScheduler.done((Integer) resume.value());
                        });
                assertTrue(suspended.await(5, java.util.concurrent.TimeUnit.SECONDS));

                // The real actor is idle, but the old compiled source context
                // still has a generation-pinned suspended Task. A checkpoint
                // must NOT confuse idle mailbox with physical quiescence.
                assertFalse(bridge.upgradeTo(candidate, WAIT));
                assertEquals(identity, bridge.address());
                assertFalse(bridge.failSealed(),
                        "busy old generation is a retryable refusal, not corruption");

                // Old source actor remains authoritative after rollback.
                bridge.admitLong(1L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(43L, next(bridge));

                release.completeFromRuntime(7);
                assertEquals(7, task.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(2, turns.get(),
                        "resume must retain original Task pin, not admit a new one");

                // Result Future settlement can beat the scheduler's final
                // physical-exit callback. Poll the authoritative ingress
                // freeze rather than assuming get() certifies quiescence.
                boolean published = false;
                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!published && System.nanoTime() < deadline) {
                    published = bridge.upgradeTo(candidate, WAIT);
                    if (!published) Thread.sleep(2L);
                }
                assertTrue(published,
                        "source-image publication requires physical Task exit");
                assertEquals(bridge.address().routeEpoch(),
                        bridge.publication().mailbox().routeEpoch());
                assertEquals(identity.actorId(), bridge.address().actorId());
                assertEquals(identity.incarnation(), bridge.address().incarnation());
                assertEquals(identity.routeEpoch() + 1, bridge.address().routeEpoch());
                bridge.admitLong(-2L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(45L, next(bridge));
            }
        }
    }

    @Test
    void physicalTaskOwnerCloseWaitsForExecutingCancelledCarrier()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit());
             OresScheduler scheduler = new OresScheduler(1)) {
            var first = manager.stageActorGeneration("scope-close.ores", V1);
            var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 2, 8, 16);
            var entered = new java.util.concurrent.CountDownLatch(1);
            var releaseCarrier = new java.util.concurrent.atomic.AtomicBoolean();
            var closed = new java.util.concurrent.atomic.AtomicBoolean();
            var closeError =
                    new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var task = bridge.startHostPinnedTaskForUpgrade(
                    scheduler, resume -> {
                        entered.countDown();
                        while (!releaseCarrier.get()) Thread.onSpinWait();
                        return OresScheduler.done(1);
                    });
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Thread closer = Thread.ofPlatform().start(() -> {
                try {
                    bridge.close();
                    closed.set(true);
                } catch (Throwable problem) {
                    closeError.set(problem);
                }
            });
            try {
                long until = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while (!task.isDone() && System.nanoTime() < until)
                    Thread.sleep(2);
                assertTrue(task.isDone(),
                        "scope close requests cancellation of the owned Task");
                assertFalse(closed.get(),
                        "cancelled Future must not retire an executing carrier");
                assertTrue(closer.isAlive(),
                        "scope close must wait for physical Task exit");
            } finally {
                releaseCarrier.set(true);
                closer.join(7000);
            }
            assertFalse(closer.isAlive(), "bounded host close should finish");
            assertNull(closeError.get(), "physical retirement must not fail");
            assertTrue(closed.get());
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> bridge.startHostPinnedTaskForUpgrade(
                            scheduler, resume -> OresScheduler.done(2)));
        }
    }

    @Test
    void publicationSnapshotNeverExposesMixedRouteAndMailboxEpoch()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("coherent-v1.ores", V1);
            var next = manager.stageActorGeneration("coherent-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 4, 8, 32)) {
                var seen = new java.util.concurrent.atomic.AtomicInteger();
                var failure =
                        new java.util.concurrent.atomic.AtomicReference<Throwable>();
                var stop = new java.util.concurrent.atomic.AtomicBoolean();
                var start = new java.util.concurrent.CountDownLatch(1);
                Thread observer = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        while (!stop.get()) {
                            var publication = bridge.publication();
                            if (publication.address().routeEpoch()
                                    != publication.mailbox().routeEpoch()) {
                                throw new AssertionError(
                                        "physical and proxy route epochs torn");
                            }
                            seen.incrementAndGet();
                        }
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                    }
                });
                try {
                    start.countDown();
                    assertTrue(bridge.upgradeTo(next, WAIT));
                    var publication = bridge.publication();
                    assertEquals(2L, publication.address().routeEpoch());
                    assertEquals(2L, publication.mailbox().routeEpoch());
                    bridge.admitLong(41L);
                    assertTrue(bridge.dispatchOne(WAIT));
                    assertEquals(42L, next(bridge));
                } finally {
                    stop.set(true);
                    observer.join(7000);
                }
                assertFalse(observer.isAlive());
                assertNull(failure.get());
                assertTrue(seen.get() > 0,
                        "observer must exercise epoch snapshots during upgrade");
            }
        }
    }

    @Test
    void realSourceActorDrainsProxyDeliveryBeforeRefundingCredits()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var initial = manager.stageActorGeneration("bridge-v1.ores", V1);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    initial, "Counter", WAIT, 2, 8, 16)) {
                var one = bridge.admitLong(41L);
                var two = bridge.admitLong(1L);
                assertEquals(1L, one.messageSequence());
                assertEquals(2L, two.messageSequence());
                assertEquals(2, bridge.snapshot().retainedMessages());
                assertThrows(IllegalStateException.class,
                        () -> bridge.admitLong(3L),
                        "queued message credits are bounded before execution");

                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
                assertEquals(1, bridge.snapshot().retainedMessages());
                assertEquals(1L, bridge.snapshot().completionWatermark());

                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(43L, next(bridge));
                assertEquals(0, bridge.snapshot().retainedMessages());
                assertEquals(2L, bridge.snapshot().completionWatermark());
                assertFalse(bridge.dispatchOne(WAIT));
                assertFalse(bridge.failSealed());
            }
        }
    }

    @Test
    void queuedMessagesCrossRealSourceUpgradeWithStableStateAndRouteIdentity()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var initial = manager.stageActorGeneration("bridge-v1.ores", V1);
            var candidate = manager.stageActorGeneration("bridge-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    initial, "Counter", WAIT, 4, 8, 32)) {
                var before = bridge.address();
                bridge.admitLong(41L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));

                // Retained immutable wire bytes are admitted but not yet
                // sent physically; route switching must preserve their FIFO.
                bridge.admitLong(-1L);
                bridge.admitLong(-2L);
                assertEquals(2, bridge.snapshot().queuedMessages());
                assertTrue(bridge.upgradeTo(candidate, WAIT));

                var after = bridge.address();
                assertEquals(before.actorId(), after.actorId());
                assertEquals(before.incarnation(), after.incarnation());
                assertEquals(before.routeEpoch() + 1L, after.routeEpoch());
                assertEquals(after.routeEpoch(), bridge.snapshot().routeEpoch());
                assertEquals(2, bridge.snapshot().queuedMessages());

                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(43L, next(bridge));
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(45L, next(bridge));
                assertEquals(0, bridge.snapshot().retainedMessages());
                assertEquals(3L, bridge.snapshot().completionWatermark());
                assertFalse(bridge.failSealed());
            }
        }
    }

    @Test
    void queuedBytesSurviveAQuiescentUpgrade()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var initial = manager.stageActorGeneration("bridge-v1.ores", V1);
            var candidate = manager.stageActorGeneration("bridge-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    initial, "Counter", WAIT, 2, 8, 16)) {
                // Queued-but-not-dispatched bytes do not block a code switch;
                // they will be delivered to the new authoritative generation.
                bridge.admitLong(41L);
                assertTrue(bridge.upgradeTo(candidate, WAIT));
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
                assertEquals(1L, bridge.snapshot().completionWatermark());
            }
        }
    }

    @Test
    void admissionRacingSourceUpgradeStaysQueuedForAuthoritativeNewRoute()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("race-v1.ores", V1);
            var second = manager.stageActorGeneration("race-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 4, 8, 32)) {
                bridge.admitLong(41L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));

                var start = new java.util.concurrent.CountDownLatch(1);
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread ingress = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        bridge.admitLong(-1L);
                    } catch (Throwable unexpected) {
                        failure.set(unexpected);
                    }
                });
                start.countDown();
                assertTrue(bridge.upgradeTo(second, WAIT));
                ingress.join(5000);
                assertFalse(ingress.isAlive(), "ingress did not finish");
                assertNull(failure.get());
                assertEquals(2L, bridge.address().routeEpoch());
                assertEquals(1, bridge.snapshot().queuedMessages());
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(43L, next(bridge));
                assertEquals(2L, bridge.snapshot().completionWatermark());
                assertEquals(0, bridge.snapshot().retainedMessages());
            }
        }
    }

    @Test
    void incompatiblePhysicalUpgradeFailsSealedInsteadOfReplayingMail()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("same-v1.ores", V1);
            var sameCode = manager.stageActorGeneration("same-code.ores", V1);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 2, 8, 16)) {
                assertThrows(IllegalArgumentException.class,
                        () -> bridge.upgradeTo(sameCode, WAIT),
                        "source actor checkpoint refuses identical code generation");
                assertTrue(bridge.failSealed());
                assertThrows(IllegalStateException.class,
                        () -> bridge.admitLong(1L));
                assertThrows(IllegalStateException.class,
                        () -> bridge.dispatchOne(WAIT));
                assertEquals(1L, bridge.address().routeEpoch());
            }
        }
    }

    @Test
    void closingCannotSilentlyDropAcceptedQueuedActorMail() throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("queued-close.ores", V1);
            var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 2, 8, 16);
            try {
                bridge.admitLong(41L);
                var before = bridge.snapshot();
                assertThrows(IllegalStateException.class, bridge::close,
                        "proxy admission is not permission to discard accepted mail");
                assertEquals(before, bridge.snapshot());
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
                assertDoesNotThrow(bridge::close);
                assertThrows(IllegalStateException.class,
                        () -> bridge.admitLong(1L));
            } finally {
                // If a test assertion fails with queued credits, retain the
                // live generation rather than silently treating mail as done.
                if (bridge.snapshot().retainedMessages() == 0) {
                    bridge.close();
                }
            }
        }
    }

    @Test
    void admissionRacingCloseIsEitherRejectedOrRetainedUntilRealDelivery()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("close-race.ores", V1);
            var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 2, 8, 16);
            var start = new java.util.concurrent.CountDownLatch(1);
            var received = new java.util.concurrent.atomic.AtomicBoolean();
            var ingressFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var closeFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread sender = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    bridge.admitLong(41L);
                    received.set(true);
                } catch (IllegalStateException closedFirst) {
                    // The close linearized before admission.
                } catch (Throwable unexpected) {
                    ingressFailure.set(unexpected);
                }
            });
            Thread closer = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    bridge.close();
                } catch (IllegalStateException mailOutstanding) {
                    // The sender admitted before close: it must not be lost.
                } catch (Throwable unexpected) {
                    closeFailure.set(unexpected);
                }
            });
            start.countDown();
            sender.join(10000);
            closer.join(10000);
            assertFalse(sender.isAlive() || closer.isAlive());
            assertNull(ingressFailure.get());
            assertNull(closeFailure.get());
            if (received.get()) {
                assertEquals(1, bridge.snapshot().retainedMessages());
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
                assertEquals(0, bridge.snapshot().retainedMessages());
            } else {
                assertEquals(0, bridge.snapshot().retainedMessages());
            }
            assertDoesNotThrow(bridge::close);
            assertDoesNotThrow(bridge::close,
                    "completed physical close must be idempotent");
            assertDoesNotThrow(first::close,
                    "finalized bridge must release its actor generation");
        }
    }

    @Test
    void closedBridgeForbidsLateMailboxAdmission()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("closed.ores", V1);
            var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 1, 8, 8);
            bridge.close();
            assertDoesNotThrow(bridge::close);
            assertThrows(IllegalStateException.class,
                    () -> bridge.admitLong(1L));
            assertThrows(IllegalStateException.class,
                    () -> bridge.dispatchOne(WAIT));
            assertEquals(0, bridge.snapshot().retainedMessages());
        }
    }
}
