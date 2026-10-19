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
 * exact acceptance linearization and cannot be confused with a generic
 * cumulative drain observer or the proxy's logical sequence.
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
