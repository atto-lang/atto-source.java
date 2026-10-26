package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Host-only admission/quota proof. No real code-loader or route publisher. */
final class HotCodeGenerationRetentionV1Test {
    private static String sha(char c) { return String.valueOf(c).repeat(64); }

    @Test void thirdDeployFailsWithoutPurgingPinnedV1ThenSucceedsAfterCleanup() {
        var budget = new HotCodeGenerationRetentionV1(2, 100);
        var old = budget.reserve(1, sha('a'), 40);
        budget.activated(old);
        var httpV1 = new HttpGenerationDrainV1(1, 2, 2);
        var request = httpV1.admit();
        var callback = request.pin(HttpGenerationDrainV1.ResourceKind.CALLBACK);
        var next = budget.reserve(2, sha('b'), 40);
        budget.activated(next);
        budget.draining(old);
        httpV1.beginDrain();
        assertThrows(IllegalStateException.class,
                () -> budget.reserve(3, sha('c'), 10));
        assertEquals(80, budget.status().heldBytes());
        request.handlerQuiescent();
        request.transportQuiescent();
        assertFalse(httpV1.readyToRetire());
        var oldCleanup = budget.physicalCleanupCompleted(old, "loader:image-a");
        assertThrows(IllegalStateException.class,
                () -> budget.acknowledgePhysicalCleanup(
                        old, 1, httpV1, oldCleanup));
        callback.close();
        budget.acknowledgePhysicalCleanup(old, 1, httpV1, oldCleanup);
        assertEquals(40, budget.status().heldBytes());
        var third = budget.reserve(3, sha('c'), 20);
        budget.activated(third);
        assertEquals(2, budget.status().retained().size());
        assertEquals(3, third.generation());
        assertEquals(2, next.generation());
    }

    @Test void failedStagedImageDoesNotRefundBeforeLoaderCleanup() {
        var budget = new HotCodeGenerationRetentionV1(1, 10);
        var staged = budget.reserve(1, sha('a'), 10);
        assertThrows(IllegalStateException.class,
                () -> budget.acknowledgePhysicalCleanup(staged, 1, null, null));
        assertThrows(IllegalStateException.class,
                () -> budget.reserve(2, sha('b'), 1));
        var cleanup = budget.physicalCleanupCompleted(staged, "loader:staged-a");
        budget.acknowledgePhysicalCleanup(staged, 1, null, cleanup);
        var retry = budget.reserve(2, sha('b'), 10);
        assertEquals(2, retry.generation());
        assertThrows(IllegalStateException.class,
                () -> budget.activated(staged));
    }

    @Test void servingCodeCannotBeReleasedEvenWithForgedCompletionHint() {
        var budget = new HotCodeGenerationRetentionV1(1, 50);
        var serving = budget.reserve(1, sha('a'), 25);
        budget.activated(serving);
        var ledger = new HttpGenerationDrainV1(1, 1, 1);
        ledger.beginDrain();
        assertThrows(IllegalStateException.class,
                () -> budget.physicalCleanupCompleted(
                        serving, "loader:serving-a"));
        assertEquals(25, budget.status().heldBytes());
    }

    @Test void servingImageCannotEnterCleanupQuarantineDirectly() {
        var budget = new HotCodeGenerationRetentionV1(1, 50);
        var serving = budget.reserve(1, sha('a'), 10);
        budget.activated(serving);
        assertThrows(IllegalStateException.class, () -> budget.quarantine(serving));
        assertEquals(HotCodeGenerationRetentionV1.Phase.SERVING,
                budget.status().retained().get(0).phase());
        assertEquals(10, budget.status().heldBytes());
        budget.draining(serving);
        budget.quarantine(serving);
        assertEquals(HotCodeGenerationRetentionV1.Phase.QUARANTINED,
                budget.status().retained().get(0).phase());
    }

    @Test void abortedReservationGenerationCannotBeReused() {
        var budget = new HotCodeGenerationRetentionV1(1, 100);
        var abandoned = budget.reserve(7, sha('a'), 12);
        budget.acknowledgePhysicalCleanup(
                abandoned, 7, null,
                budget.physicalCleanupCompleted(abandoned, "loader:abandoned-a"));
        assertThrows(IllegalStateException.class,
                () -> budget.reserve(7, sha('b'), 12));
        var next = budget.reserve(8, sha('b'), 12);
        assertEquals(8, next.generation());
    }

    @Test void quarantinedImageOccupiesQuotaUntilRealCleanup() {
        var budget = new HotCodeGenerationRetentionV1(2, 100);
        var old = budget.reserve(1, sha('a'), 40);
        budget.activated(old);
        budget.draining(old);
        var stalled = new HttpGenerationDrainV1(1, 1, 1);
        var stream = stalled.admit();
        stalled.beginDrain();
        budget.quarantine(old);
        var newer = budget.reserve(2, sha('b'), 40);
        budget.activated(newer);
        assertThrows(IllegalStateException.class,
                () -> budget.reserve(3, sha('c'), 10));
        stream.handlerQuiescent();
        stream.transportQuiescent();
        assertThrows(IllegalStateException.class,
                () -> budget.acknowledgePhysicalCleanup(old, 1, stalled, null));
        assertEquals(80, budget.status().heldBytes());
        var cleanup = budget.physicalCleanupCompleted(old, "loader:quarantined-a");
        budget.acknowledgePhysicalCleanup(old, 1, stalled, cleanup);
        assertEquals(40, budget.status().heldBytes());
    }

    @Test void foreignOrStaleTokenAndMismatchedHttpGenerationFailClosed() {
        var a = new HotCodeGenerationRetentionV1(2, 100);
        var b = new HotCodeGenerationRetentionV1(2, 100);
        var old = a.reserve(1, sha('a'), 10);
        assertThrows(IllegalStateException.class, () -> b.activated(old));
        a.activated(old);
        a.draining(old);
        var wrong = new HttpGenerationDrainV1(2, 1, 1);
        wrong.beginDrain();
        var cleanup = a.physicalCleanupCompleted(old, "loader:old-a");
        assertThrows(IllegalStateException.class,
                () -> a.acknowledgePhysicalCleanup(old, 1, wrong, cleanup));
        var right = new HttpGenerationDrainV1(1, 1, 1);
        right.beginDrain();
        a.acknowledgePhysicalCleanup(old, 1, right, cleanup);
        assertThrows(IllegalStateException.class,
                () -> a.acknowledgePhysicalCleanup(old, 1, right, cleanup));
        assertThrows(IllegalStateException.class,
                () -> a.reserve(1, sha('b'), 10)); // cannot recycle epoch after cleanup
        var replacement = a.reserve(2, sha('b'), 10);
        assertThrows(IllegalStateException.class, () -> a.activated(old));
        a.activated(replacement);
    }

    @Test void bytesQuotaFailsWithoutOverflowAndStatusIsImmutable() {
        var budget = new HotCodeGenerationRetentionV1(4, Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,
                () -> budget.reserve(1, "wrong", 1));
        assertThrows(IllegalArgumentException.class,
                () -> budget.reserve(1, sha('a'), -1));
        var large = budget.reserve(1, sha('a'), Long.MAX_VALUE - 8);
        assertThrows(IllegalStateException.class,
                () -> budget.reserve(2, sha('b'), 9));
        assertEquals(Long.MAX_VALUE - 8, budget.status().heldBytes());
        var snapshot = budget.status();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.retained().clear());
        assertEquals(HotCodeGenerationRetentionV1.Phase.STAGED,
                snapshot.retained().get(0).phase());
        budget.acknowledgePhysicalCleanup(
                large, 1, null,
                budget.physicalCleanupCompleted(large, "loader:large-a"));
        assertEquals(0, budget.status().heldBytes());
        assertEquals(1, snapshot.retained().size());
    }

    @Test void imageGenerationAndRouteEpochMayDifferAfterAbortedImage() {
        var budget = new HotCodeGenerationRetentionV1(2, 100);
        var image7 = budget.reserve(7, sha('a'), 10);
        budget.activated(image7);
        budget.draining(image7);
        var route2 = new HttpGenerationDrainV1(2, 1, 1);
        route2.beginDrain();

        var receipt = budget.physicalCleanupCompleted(image7, "loader:image7-route2");
        assertEquals(7, receipt.imageGeneration());
        assertEquals(sha('a'), receipt.imageSha256());
        budget.acknowledgePhysicalCleanup(image7, 2, route2, receipt);
        assertTrue(budget.status().retained().isEmpty());
    }

    @Test void cleanupReceiptCannotCrossImagesOwnersOrReplay() {
        var a = new HotCodeGenerationRetentionV1(2, 100);
        var b = new HotCodeGenerationRetentionV1(2, 100);
        var first = a.reserve(1, sha('a'), 10);
        var second = a.reserve(2, sha('b'), 10);
        var foreign = b.reserve(1, sha('a'), 10);
        var firstReceipt = a.physicalCleanupCompleted(first, "loader:first");

        assertThrows(IllegalStateException.class,
                () -> a.acknowledgePhysicalCleanup(second, 2, null, firstReceipt));
        assertThrows(IllegalStateException.class,
                () -> b.acknowledgePhysicalCleanup(foreign, 1, null, firstReceipt));

        a.acknowledgePhysicalCleanup(first, 1, null, firstReceipt);
        assertThrows(IllegalStateException.class,
                () -> a.acknowledgePhysicalCleanup(first, 1, null, firstReceipt));
        assertEquals(1, a.status().retained().size());
    }

    @Test void concurrentStagersCannotOverbookImageCount() throws Exception {
        var budget = new HotCodeGenerationRetentionV1(1, 64);
        var ready = new CountDownLatch(16);
        var start = new CountDownLatch(1);
        var success = new AtomicInteger();
        var unexpected = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            final int generation = i;
            var worker = new Thread(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("barrier timeout");
                    budget.reserve(generation, sha('a'), 1);
                    success.incrementAndGet();
                } catch (IllegalStateException expectedCapacity) {
                    // Exactly one reservation may succeed.
                } catch (Throwable failure) {
                    unexpected.incrementAndGet();
                }
            });
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Thread worker : workers) {
            worker.join(5000);
            assertFalse(worker.isAlive());
        }
        assertEquals(0, unexpected.get());
        assertEquals(1, success.get());
        assertEquals(1, budget.status().retained().size());
        assertEquals(1, budget.status().heldBytes());
    }
}
