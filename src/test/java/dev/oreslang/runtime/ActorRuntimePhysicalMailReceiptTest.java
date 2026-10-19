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
            assertFalse(first.terminal().cancel(false),
                    "a host observer may not cancel runtime message completion");
            first.terminal().get(5, TimeUnit.SECONDS);
            second.terminal().get(5, TimeUnit.SECONDS);
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
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> receipt.terminal().get(10, TimeUnit.MILLISECONDS));
                assertFalse(receipt.terminal().cancel(false),
                        "a timeout must not cancel or forge physical completion");
                release.countDown();
                receipt.terminal().get(5, TimeUnit.SECONDS);
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
            } finally {
                drainsField.set(cell, original);
                release.countDown();
            }

            // Because failed teardown published no terminal actor state, the
            // original receive may finish normally and settle its exact receipt.
            receipt.terminal().get(5, TimeUnit.SECONDS);
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
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
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> receipt.terminal().get(5, TimeUnit.SECONDS),
                    "terminal actor failure must fail, not acknowledge delivery");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
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
