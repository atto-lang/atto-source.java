package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Product ActorRuntime physical mailbox tests: receipts are minted by the
 * exact acceptance linearization, use an independently bounded exact-message
 * ledger, and cannot be confused with generic cumulative drain observers or
 * the proxy's logical sequence.
 */
@Timeout(40)
final class ActorRuntimePhysicalMailReceiptTest {
    @Test
    void acceptedPhysicalSequenceHasItsOwnNonCancellableTerminalWitness()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var sum = new AtomicInteger();
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> sum.addAndGet(message));
            actor.getReady().get(5, TimeUnit.SECONDS);

            var first = runtime.sendWithPhysicalReceipt(actor, 3);
            var second = runtime.sendWithPhysicalReceipt(actor, 4);
            assertSame(actor, first.physical());
            assertSame(actor, second.physical());
            assertEquals(0L, first.acceptedSequence());
            assertEquals(1L, second.acceptedSequence());
            assertNotSame(first.terminal(), second.terminal());
            assertNull(first.terminalDisposition());
            assertNull(second.terminalDisposition());
            assertFalse(first.terminal().cancel(false),
                    "a host observer may not cancel runtime message completion");
            first.terminal().get(5, TimeUnit.SECONDS);
            second.terminal().get(5, TimeUnit.SECONDS);
            assertEquals(ActorRuntime.PhysicalMailDisposition.PROCESSED,
                    first.terminalDisposition());
            assertEquals(ActorRuntime.PhysicalMailDisposition.PROCESSED,
                    second.terminalDisposition());
            assertEquals(7, sum.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void admittedButStillExecutingMessageCannotBeAcknowledgedEarly()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("blocked actor not released");
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            try {
                var receipt = runtime.sendWithPhysicalReceipt(actor, 99);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(receipt.terminal().isDone(),
                        "mailbox acceptance is not physical processing completion");
                assertNull(receipt.terminalDisposition(),
                        "pending receipt cannot expose a terminal class");
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> receipt.terminal().get(10, TimeUnit.MILLISECONDS));
                assertFalse(receipt.terminal().cancel(false),
                        "a timeout must not cancel or forge physical completion");
                release.countDown();
                receipt.terminal().get(5, TimeUnit.SECONDS);
                assertEquals(ActorRuntime.PhysicalMailDisposition.PROCESSED,
                        receipt.terminalDisposition());
            } finally {
                release.countDown();
                actor.stop();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void publicDrainQuotaCannotStarveExactSupervisorReceipt()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var firstEntered = new CountDownLatch(1);
            var releaseFirst = new CountDownLatch(1);
            var secondEntered = new CountDownLatch(1);
            var releaseSecond = new CountDownLatch(1);
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        if (message == 1) {
                            firstEntered.countDown();
                            if (!releaseFirst.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("first message not released");
                        } else if (message == 2) {
                            secondEntered.countDown();
                            if (!releaseSecond.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("second message not released");
                        }
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

            // Exhaust the entire ordinary cumulative-drain observer budget
            // against the blocked first message. Supervisor exact-message
            // receipt metadata is a separate bounded authority domain and
            // must remain available.
            var publicDrains = new OresFuture<?>[1_024];
            for (int i = 0; i < publicDrains.length; ++i) {
                publicDrains[i] = actor.drain();
                assertFalse(publicDrains[i].isDone());
            }

            var second = runtime.sendWithPhysicalReceipt(actor, 2);
            assertEquals(1L, second.acceptedSequence());
            assertFalse(second.terminal().isDone());

            releaseFirst.countDown();
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            // All cumulative drains targeted sequence 0 and may now settle,
            // but the exact receipt for physical sequence 1 must not.
            for (var drain : publicDrains) {
                drain.get(5, TimeUnit.SECONDS);
            }
            assertFalse(second.terminal().isDone(),
                    "prefix-drain settlement cannot forge exact message completion");

            releaseSecond.countDown();
            second.terminal().get(5, TimeUnit.SECONDS);
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            // Latches are local to the actor fixture; timeout failures cause
            // ActorRuntime.close() to revoke the actor and receipt safely.
        }
    }

    private static Object actorCellForTest(
            ActorRuntime runtime, ActorRuntime.ActorRef<?> actor)
            throws Exception {
        var actorsField = ActorRuntime.class.getDeclaredField("actors");
        actorsField.setAccessible(true);
        var actors = (java.util.Map<?, ?>) actorsField.get(runtime);
        Object cell = actors.get(actor.id());
        assertNotNull(cell);
        return cell;
    }

    @Test
    void teardownSnapshotOomeCannotPublishDeadActorWithPendingExactReceipt()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("blocked receive not released");
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            var receipt = runtime.sendWithPhysicalReceipt(actor, 7);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            Object cell = actorCellForTest(runtime, actor);
            var drainsField = cell.getClass().getDeclaredField("pendingDrains");
            drainsField.setAccessible(true);
            Object original = drainsField.get(cell);

            var failingSnapshot = new java.util.ArrayList<Object>() {
                private boolean fail = true;

                private void failOnce() {
                    if (fail) {
                        fail = false;
                        throw new OutOfMemoryError(
                                "injected teardown snapshot allocation failure");
                    }
                }

                @Override public Object[] toArray() {
                    failOnce();
                    return super.toArray();
                }

                @Override public <T> T[] toArray(T[] target) {
                    failOnce();
                    return super.toArray(target);
                }
            };

            drainsField.set(cell, failingSnapshot);
            try {
                assertThrows(OutOfMemoryError.class, actor::stop);
                assertTrue(actor.isAlive(),
                        "failed snapshot preparation must not publish stopped=true");
                assertFalse(receipt.terminal().isDone(),
                        "exact receipt remains owned while the live receive is pending");
                assertNull(receipt.terminalDisposition(),
                        "failed teardown preparation cannot publish a terminal class");
            } finally {
                drainsField.set(cell, original);
                release.countDown();
            }

            // Because failed teardown published no terminal actor state, the
            // original receive may finish normally and settle its exact receipt.
            receipt.terminal().get(5, TimeUnit.SECONDS);
            assertEquals(ActorRuntime.PhysicalMailDisposition.PROCESSED,
                    receipt.terminalDisposition());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void runtimeCloseRetryAfterTeardownOomeStillShutsDispatchersAndReceipts()
            throws Exception {
        var runtime = new ActorRuntime();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var actor = runtime.<Integer>spawnShared(
                () -> (message, context) -> {
                    entered.countDown();
                    release.await();
                });
        Object original = null;
        java.lang.reflect.Field drainsField = null;
        try {
            actor.getReady().get(5, TimeUnit.SECONDS);
            var receipt = runtime.sendWithPhysicalReceipt(actor, 1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            Object cell = actorCellForTest(runtime, actor);
            drainsField = cell.getClass().getDeclaredField("pendingDrains");
            drainsField.setAccessible(true);
            original = drainsField.get(cell);

            var failingSnapshot = new java.util.ArrayList<Object>() {
                private boolean fail = true;

                private void failOnce() {
                    if (fail) {
                        fail = false;
                        throw new OutOfMemoryError(
                                "injected first runtime-close teardown failure");
                    }
                }

                @Override public Object[] toArray() {
                    failOnce();
                    return super.toArray();
                }

                @Override public <T> T[] toArray(T[] target) {
                    failOnce();
                    return super.toArray(target);
                }
            };
            drainsField.set(cell, failingSnapshot);

            assertThrows(OutOfMemoryError.class, runtime::close);
            assertTrue(actor.isAlive(),
                    "failed actor teardown preparation cannot publish actor stop");
            assertFalse(receipt.terminal().isDone());
            assertNull(receipt.terminalDisposition());

            drainsField.set(cell, original);
            original = null;

            // The first close already sealed global runtime admission, but it
            // did not consume dispatcher-shutdown authority. The retry must
            // stop the actor, settle the exact receipt and terminate carriers.
            assertDoesNotThrow(runtime::close);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> receipt.terminal().get(5, TimeUnit.SECONDS));
            assertEquals(ActorRuntime.PhysicalMailDisposition.RUNTIME_CLOSED,
                    receipt.terminalDisposition());
            assertDoesNotThrow(runtime::close,
                    "completed runtime close remains idempotent after retry");
        } finally {
            release.countDown();
            if (drainsField != null && original != null) {
                try {
                    Object cell = actorCellForTest(runtime, actor);
                    drainsField.set(cell, original);
                } catch (Throwable ignored) {
                    // Runtime may already have finalized and removed the cell.
                }
            }
            try {
                runtime.close();
            } catch (Throwable ignored) {
                // Preserve the primary assertion/fault-injection result.
            }
        }
    }

    @Test
    void failedActorReceiveCannotForgeSuccessfulPhysicalCompletion()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        throw new IllegalStateException("injected receive failure");
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            var receipt = runtime.sendWithPhysicalReceipt(actor, 1);
            assertSame(actor, receipt.physical());
            var failure = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> receipt.terminal().get(5, TimeUnit.SECONDS),
                    "terminal actor failure must fail, not acknowledge delivery");
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals("injected receive failure", failure.getCause().getMessage());
            assertEquals(ActorRuntime.PhysicalMailDisposition.FAILED,
                    receipt.terminalDisposition());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedActiveMessageAlsoFailsLaterQueuedExactReceipts()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var entered = new CountDownLatch(1);
            var releaseFailure = new CountDownLatch(1);
            var processedSuccessor = new AtomicInteger();
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        if (message == 1) {
                            entered.countDown();
                            if (!releaseFailure.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("first message not released");
                            throw new IllegalStateException("injected active receive failure");
                        }
                        processedSuccessor.incrementAndGet();
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            try {
                var first = runtime.sendWithPhysicalReceipt(actor, 1);
                assertTrue(entered.await(5, TimeUnit.SECONDS),
                        "first accepted physical message must be executing");
                var queued = runtime.sendWithPhysicalReceipt(actor, 2);
                assertEquals(first.acceptedSequence() + 1L,
                        queued.acceptedSequence());
                assertFalse(first.terminal().isDone());
                assertFalse(queued.terminal().isDone(),
                        "queued successor cannot be acknowledged by earlier prefix");

                releaseFailure.countDown();
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> first.terminal().get(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> queued.terminal().get(5, TimeUnit.SECONDS),
                        "terminal actor failure must fail an accepted but undelivered successor");
                assertEquals(ActorRuntime.PhysicalMailDisposition.FAILED,
                        first.terminalDisposition());
                assertEquals(ActorRuntime.PhysicalMailDisposition.FAILED,
                        queued.terminalDisposition());
                assertEquals(0, processedSuccessor.get(),
                        "queued mail must not run after fail-stop");
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                releaseFailure.countDown();
            }
        }
    }

    @Test
    void explicitStopMarksAcceptedActiveMailStoppedNotProcessed()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        entered.countDown();
                        release.await();
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            var receipt = runtime.sendWithPhysicalReceipt(actor, 1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            var stopFailure = new AtomicReference<Throwable>();
            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            });
            try {
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> receipt.terminal().get(5, TimeUnit.SECONDS));
                assertEquals(ActorRuntime.PhysicalMailDisposition.STOPPED,
                        receipt.terminalDisposition());
            } finally {
                release.countDown();
            }
            stopper.join(5_000);
            assertFalse(stopper.isAlive());
            assertNull(stopFailure.get());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void structuredCancellationMarksAcceptedMailCancelled()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        entered.countDown();
                        release.await();
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            var receipt = runtime.sendWithPhysicalReceipt(actor, 1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                assertTrue(runtime.cancel(actor));
                var failure = assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> receipt.terminal().get(5, TimeUnit.SECONDS));
                var cancelled = assertInstanceOf(
                        ActorRuntime.ActorCancelledException.class,
                        failure.getCause());
                assertEquals(ActorRuntime.CancellationMode.STRUCTURED,
                        cancelled.mode());
                assertEquals(ActorRuntime.PhysicalMailDisposition.CANCELLED,
                        receipt.terminalDisposition());
            } finally {
                release.countDown();
            }
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void forceCancellationMarksQueuedAcceptedMailForceCancelled()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            runtime.setForceCancellationTreeHook(request -> true);
            var firstEntered = new CountDownLatch(1);
            var releaseFirst = new CountDownLatch(1);
            var successorRuns = new AtomicInteger();
            var actor = runtime.<Integer>spawnPrivate(
                    () -> (message, context) -> {
                        if (message == 1) {
                            firstEntered.countDown();
                            releaseFirst.await();
                        } else {
                            successorRuns.incrementAndGet();
                        }
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            var queued = runtime.sendWithPhysicalReceipt(actor, 2);
            try {
                assertTrue(runtime.cancel(
                        actor, ActorRuntime.CancellationMode.FORCE_ISOLATED));
                var failure = assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> queued.terminal().get(5, TimeUnit.SECONDS));
                var cancelled = assertInstanceOf(
                        ActorRuntime.ActorCancelledException.class,
                        failure.getCause());
                assertEquals(ActorRuntime.CancellationMode.FORCE_ISOLATED,
                        cancelled.mode());
                assertEquals(ActorRuntime.PhysicalMailDisposition.FORCE_CANCELLED,
                        queued.terminalDisposition());
                assertEquals(0, successorRuns.get());
            } finally {
                releaseFirst.countDown();
            }
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void runtimeCloseMarksQueuedAcceptedMailRuntimeClosed()
            throws Exception {
        var runtime = new ActorRuntime();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var successorRuns = new AtomicInteger();
        var actor = runtime.<Integer>spawnShared(
                () -> (message, context) -> {
                    if (message == 1) {
                        firstEntered.countDown();
                        releaseFirst.await();
                    } else {
                        successorRuns.incrementAndGet();
                    }
                });
        try {
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            var queued = runtime.sendWithPhysicalReceipt(actor, 2);

            // Runtime shutdown interrupts dispatcher mechanics after publishing
            // the runtime-specific receipt disposition. The first blocked turn
            // is therefore allowed to unwind without processing its successor.
            assertDoesNotThrow(runtime::close);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> queued.terminal().get(5, TimeUnit.SECONDS));
            assertEquals(ActorRuntime.PhysicalMailDisposition.RUNTIME_CLOSED,
                    queued.terminalDisposition());
            assertEquals(0, successorRuns.get());
        } finally {
            releaseFirst.countDown();
            try {
                runtime.close();
            } catch (IllegalStateException ignored) {
                // A failed assertion may leave a carrier outside the normal
                // test ordering; the first close already sealed the runtime.
            }
        }
    }

    @Test
    void completionRacingStructuredCancelSettlesExactlyOneDisposition()
            throws Exception {
        for (int attempt = 0; attempt < 32; ++attempt) {
            try (var runtime = new ActorRuntime()) {
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var go = new CountDownLatch(1);
                var actor = runtime.<Integer>spawnShared(
                        () -> (message, context) -> {
                            entered.countDown();
                            release.await();
                        });
                actor.getReady().get(5, TimeUnit.SECONDS);
                var receipt = runtime.sendWithPhysicalReceipt(actor, 1);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var callbacks = new AtomicInteger();
                receipt.terminal().whenCompleteRuntime(
                        (ignored, failure) -> callbacks.incrementAndGet());

                Thread finisher = Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        release.countDown();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                Thread canceller = Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        runtime.cancel(actor);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                go.countDown();
                finisher.join(5_000);
                canceller.join(5_000);
                assertFalse(finisher.isAlive() || canceller.isAlive());

                try {
                    receipt.terminal().get(5, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException expected) {
                    // Cancellation may win the lifecycleLock race.
                }
                var disposition = receipt.terminalDisposition();
                assertTrue(
                        disposition == ActorRuntime.PhysicalMailDisposition.PROCESSED
                                || disposition
                                == ActorRuntime.PhysicalMailDisposition.CANCELLED,
                        "completion/cancel race published impossible disposition "
                                + disposition);
                assertEquals(1, callbacks.get(),
                        "exact receipt must notify one terminal settlement");
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void completionRacingStopSettlesExactlyOneDisposition()
            throws Exception {
        for (int attempt = 0; attempt < 24; ++attempt) {
            try (var runtime = new ActorRuntime()) {
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var go = new CountDownLatch(1);
                var actor = runtime.<Integer>spawnShared(
                        () -> (message, context) -> {
                            entered.countDown();
                            release.await();
                        });
                actor.getReady().get(5, TimeUnit.SECONDS);
                var receipt = runtime.sendWithPhysicalReceipt(actor, 1);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var callbacks = new AtomicInteger();
                receipt.terminal().whenCompleteRuntime(
                        (ignored, failure) -> callbacks.incrementAndGet());
                var stopFailure = new AtomicReference<Throwable>();

                Thread finisher = Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        release.countDown();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                Thread stopper = Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        actor.stop();
                    } catch (Throwable failure) {
                        stopFailure.compareAndSet(null, failure);
                    }
                });
                go.countDown();
                finisher.join(5_000);
                stopper.join(5_000);
                assertFalse(finisher.isAlive() || stopper.isAlive());
                assertNull(stopFailure.get());

                try {
                    receipt.terminal().get(5, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException expected) {
                    // Stop may win before physical message processing publishes.
                }
                var disposition = receipt.terminalDisposition();
                assertTrue(
                        disposition == ActorRuntime.PhysicalMailDisposition.PROCESSED
                                || disposition
                                == ActorRuntime.PhysicalMailDisposition.STOPPED,
                        "completion/stop race published impossible disposition "
                                + disposition);
                assertEquals(1, callbacks.get());
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void completionRacingForceCancelSettlesExactlyOneDisposition()
            throws Exception {
        for (int attempt = 0; attempt < 24; ++attempt) {
            try (var runtime = new ActorRuntime()) {
                runtime.setForceCancellationTreeHook(request -> true);
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var go = new CountDownLatch(1);
                var actor = runtime.<Integer>spawnPrivate(
                        () -> (message, context) -> {
                            entered.countDown();
                            release.await();
                        });
                actor.getReady().get(5, TimeUnit.SECONDS);
                var receipt = runtime.sendWithPhysicalReceipt(actor, 1);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var callbacks = new AtomicInteger();
                receipt.terminal().whenCompleteRuntime(
                        (ignored, failure) -> callbacks.incrementAndGet());
                var cancelFailure = new AtomicReference<Throwable>();

                Thread finisher = Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        release.countDown();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                Thread canceller = Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        runtime.cancel(
                                actor, ActorRuntime.CancellationMode.FORCE_ISOLATED);
                    } catch (Throwable failure) {
                        cancelFailure.compareAndSet(null, failure);
                    }
                });
                go.countDown();
                finisher.join(5_000);
                canceller.join(5_000);
                assertFalse(finisher.isAlive() || canceller.isAlive());
                assertNull(cancelFailure.get());

                try {
                    receipt.terminal().get(5, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException expected) {
                    // Force cancellation may win the lifecycleLock race.
                }
                var disposition = receipt.terminalDisposition();
                assertTrue(
                        disposition == ActorRuntime.PhysicalMailDisposition.PROCESSED
                                || disposition
                                == ActorRuntime.PhysicalMailDisposition.FORCE_CANCELLED,
                        "completion/force-cancel race published impossible disposition "
                                + disposition);
                assertEquals(1, callbacks.get());
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    /**
     * A broken embedder/dispatcher may fail with more than the conventional
     * RejectedExecutionException. Even if its execute() method enqueues a
     * runnable and then throws, physical mailbox admission must fail-stop the
     * actor and never let that old dispatch ticket run after termination.
     */
    private static void assertUnexpectedCarrierSubmissionFailure(Throwable injected)
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var processed = new AtomicInteger();
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> processed.incrementAndGet());
            actor.getReady().get(5, TimeUnit.SECONDS);

            var dispatcherField = ActorRuntime.class.getDeclaredField(
                    "sharedDispatcher");
            dispatcherField.setAccessible(true);
            var original = (java.util.concurrent.ExecutorService)
                    dispatcherField.get(runtime);
            var captured = new AtomicReference<Runnable>();
            java.util.concurrent.ExecutorService faulted =
                    new java.util.concurrent.AbstractExecutorService() {
                private volatile boolean stopped;

                @Override public void execute(Runnable command) {
                    captured.set(command);
                    if (injected instanceof Error fatal) throw fatal;
                    throw (RuntimeException) injected;
                }

                @Override public void shutdown() { stopped = true; }
                @Override public java.util.List<Runnable> shutdownNow() {
                    stopped = true;
                    return java.util.List.of();
                }
                @Override public boolean isShutdown() { return stopped; }
                @Override public boolean isTerminated() { return stopped; }
                @Override public boolean awaitTermination(
                        long timeout, TimeUnit unit) { return stopped; }
            };
            dispatcherField.set(runtime, faulted);
            try {
                // The physical mailbox already accepted this mail before
                // schedule() asks the deliberately broken executor to run it.
                Throwable actual = assertThrows(Throwable.class,
                        () -> runtime.sendWithPhysicalReceipt(actor, 1));
                assertSame(injected, actual);
            } finally {
                dispatcherField.set(runtime, original);
            }

            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "a failed carrier submission must terminally close the actor");
            assertEquals(0, processed.get());
            assertNotNull(captured.get(),
                    "the executor captured a possibly accepted dispatch");
            captured.get().run(); // delayed stale runnable must remain inert
            assertEquals(0, processed.get(),
                    "failed dispatch cannot revive a stopped physical actor");
            assertThrows(IllegalStateException.class,
                    () -> runtime.sendWithPhysicalReceipt(actor, 2));
        }
    }

    @Test
    void unexpectedDispatchRuntimeFailureFailsStopAcceptedPhysicalMail()
            throws Exception {
        assertUnexpectedCarrierSubmissionFailure(
                new IllegalStateException("injected executor implementation failure"));
    }

    @Test
    void unexpectedDispatchErrorFailsStopAcceptedPhysicalMail()
            throws Exception {
        assertUnexpectedCarrierSubmissionFailure(
                new AssertionError("injected carrier callback allocation failure"));
    }

    @Test
    void physicalSequencesArePerActorNotLogicalProxySequenceNumbers()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var left = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            var right = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            left.getReady().get(5, TimeUnit.SECONDS);
            right.getReady().get(5, TimeUnit.SECONDS);
            var a = runtime.sendWithPhysicalReceipt(left, 11);
            var b = runtime.sendWithPhysicalReceipt(right, 22);
            assertNotSame(a.physical(), b.physical());
            assertEquals(0L, a.acceptedSequence());
            assertEquals(0L, b.acceptedSequence());
            a.terminal().get(5, TimeUnit.SECONDS);
            b.terminal().get(5, TimeUnit.SECONDS);
            left.stop();
            right.stop();
            assertTrue(left.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(right.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Boolean> carrierMarker(
            Class<?> owner, String fieldName) throws Exception {
        var field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ThreadLocal<Boolean>) field.get(null);
    }

    @Test
    void carrierOnlyCallbacksCannotMintPhysicalMessageReceipts()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var received = new AtomicInteger();
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> received.incrementAndGet());
            actor.getReady().get(5, TimeUnit.SECONDS);
            for (var entry : new Object[][] {
                    {OresScheduler.class, "SCHEDULER_CARRIER"},
                    {AsyncRuntime.class, "ASYNC_CARRIER"}}) {
                var carrier = carrierMarker(
                        (Class<?>) entry[0], (String) entry[1]);
                Boolean before = carrier.get();
                try {
                    carrier.set(Boolean.TRUE);
                    assertThrows(SecurityException.class,
                            () -> runtime.sendWithPhysicalReceipt(actor, 1),
                            "cleared active-turn marker must not confer CONTROL authority");
                } finally {
                    if (before == null) carrier.remove();
                    else carrier.set(before);
                }
            }
            var receipt = runtime.sendWithPhysicalReceipt(actor, 3);
            assertEquals(0L, receipt.acceptedSequence(),
                    "rejected carrier calls must not accept physical mail");
            receipt.terminal().get(5, TimeUnit.SECONDS);
            assertEquals(1, received.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentTrackedSendsHaveUniqueAcceptedSequencesAndComplete()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var total = new AtomicInteger();
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> total.addAndGet(message));
            actor.getReady().get(5, TimeUnit.SECONDS);
            var go = new CountDownLatch(1);
            var failed = new AtomicReference<Throwable>();
            var receipts = new ActorRuntime.PhysicalMailReceipt[16];
            var threads = new Thread[receipts.length];
            for (int i = 0; i < threads.length; i++) {
                final int index = i;
                threads[i] = Thread.ofPlatform().start(() -> {
                    try {
                        if (!go.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("tracked-send race start timeout");
                        receipts[index] = runtime.sendWithPhysicalReceipt(actor, 1);
                    } catch (Throwable failure) {
                        failed.compareAndSet(null, failure);
                    }
                });
            }
            go.countDown();
            for (var thread : threads) {
                thread.join(5000);
                assertFalse(thread.isAlive());
            }
            assertNull(failed.get());
            var sequences = new HashSet<Long>();
            for (var receipt : receipts) {
                assertNotNull(receipt);
                assertSame(actor, receipt.physical());
                assertTrue(sequences.add(receipt.acceptedSequence()),
                        "physical admission sequence cannot repeat");
                receipt.terminal().get(5, TimeUnit.SECONDS);
            }
            assertEquals(16, sequences.size());
            assertEquals(16, total.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
