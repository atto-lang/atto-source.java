package dev.oreslang;

import dev.oreslang.runtime.DisjointIntRegion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class DisjointIntRegionTest {
    @Test
    @SuppressWarnings("try") // Explicit close is the retirement event under test.
    void tenWorkersUpdateDisjointRangesWithoutElementLocks() throws Exception {
        final int workers = 10;
        final int width = 1_000;
        try (var region = new DisjointIntRegion(workers * width)) {
            var original = region.takeRoot();
            var parts = original.split(workers);
            assertEquals(workers, parts.size());
            assertThrows(IllegalStateException.class,
                    () -> original.withWrite(loan -> loan.set(0, 99)));

            var start = new CountDownLatch(1);
            var done = new CountDownLatch(workers);
            var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            var threads = new ArrayList<Thread>();
            for (int i = 0; i < workers; i++) {
                final int index = i;
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        parts.get(index).withWrite(loan -> {
                            assertEquals(width, loan.length());
                            for (int j = 0; j < loan.length(); j++) {
                                loan.set(j, index + 1);
                            }
                        });
                    } catch (Throwable failure) { failures.add(failure); }
                    finally { done.countDown(); }
                }));
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            for (var thread : threads) thread.join();
            assertTrue(failures.isEmpty(), failures.toString());
            region.close();
            int[] result = region.snapshotAfterClose();
            for (int i = 0; i < result.length; i++) {
                assertEquals((i / width) + 1, result[i], "index=" + i);
            }
        }
    }

    @Test
    void capturedAndForeignCarrierLoansFailClosed() throws Exception {
        try (var region = new DisjointIntRegion(3)) {
            var permit = region.takeRoot();
            AtomicReference<DisjointIntRegion.Loan> escaped = new AtomicReference<>();
            permit.withWrite(loan -> {
                escaped.set(loan);
                loan.set(0, 7);
                AtomicReference<Throwable> foreign = new AtomicReference<>();
                Thread other = Thread.ofPlatform().start(() -> {
                    try { loan.set(0, 99); }
                    catch (Throwable failure) { foreign.set(failure); }
                });
                try { other.join(); } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                assertInstanceOf(IllegalStateException.class, foreign.get());
            });
            assertThrows(IllegalStateException.class, () -> escaped.get().get(0));
            assertThrows(IllegalStateException.class, () -> escaped.get().length());
            permit.withWrite(loan -> assertEquals(7, loan.get(0)));
            assertThrows(IllegalStateException.class, region::takeRoot);
        }
    }

    @Test
    void exceptionsAndStaleReadsCannotRetainExclusiveAccess() {
        try (var region = new DisjointIntRegion(4)) {
            var permit = region.takeRoot();
            var escaped = new AtomicReference<DisjointIntRegion.Loan>();
            var error = assertThrows(IllegalArgumentException.class, () ->
                permit.withWrite(loan -> {
                    escaped.set(loan);
                    loan.set(0, 17);
                    throw new IllegalArgumentException("simulated callback failure");
                }));
            assertEquals("simulated callback failure", error.getMessage());
            assertThrows(IllegalStateException.class, () -> escaped.get().length());
            assertThrows(IllegalStateException.class, () -> escaped.get().get(0));
            permit.withWrite(loan -> {
                assertEquals(17, loan.get(0));
                loan.set(1, 29);
            });
            permit.close();
            assertThrows(IllegalStateException.class, () -> permit.split(2));
            assertThrows(IllegalStateException.class, () -> permit.withWrite(loan -> { }));
        }
    }

    @Test
    void concurrentSplitCanHaveOnlyOneWinner() throws Exception {
        try (var region = new DisjointIntRegion(100)) {
            var root = region.takeRoot();
            var start = new CountDownLatch(1);
            var finish = new CountDownLatch(10);
            var successes = new java.util.concurrent.atomic.AtomicInteger();
            var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            for (int i = 0; i < 10; i++) {
                Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        root.split(2);
                        successes.incrementAndGet();
                    } catch (IllegalStateException lost) {
                        // Exactly one root can be consumed to mint children.
                    } catch (Throwable unexpected) { failures.add(unexpected); }
                    finally { finish.countDown(); }
                });
            }
            start.countDown();
            assertTrue(finish.await(10, TimeUnit.SECONDS));
            assertTrue(failures.isEmpty(), failures.toString());
            assertEquals(1, successes.get());
        }
    }

    @Test
    void closePreventsNewCallbackWithoutInterruptingAdmittedWork() throws Exception {
        var region = new DisjointIntRegion(2);
        var root = region.takeRoot();
        var started = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                root.withWrite(loan -> {
                    loan.set(0, 1);
                    started.countDown();
                    try { resume.await(); }
                    catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                    // This is deliberately AFTER close() in another thread.
                    loan.set(1, 2);
                });
            } catch (Throwable unexpected) { failure.set(unexpected); }
            finally { finished.countDown(); }
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            region.close();
            assertThrows(IllegalStateException.class, region::takeRoot);
            assertThrows(IllegalStateException.class, region::snapshotAfterClose);
            assertThrows(IllegalStateException.class, () -> root.split(2));
        } finally {
            resume.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            worker.join();
        }
        assertNull(failure.get());
        int[] snapshot = region.snapshotAfterClose();
        assertEquals(1, snapshot[0]);
        assertEquals(2, snapshot[1]);
    }

    @Test
    void retirementWaitsForPhysicalUnwindBeforeSnapshot() throws Exception {
        var region = new DisjointIntRegion(5);
        var permit = region.takeRoot();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicReference<Throwable> writer = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                permit.withWrite(loan -> {
                    loan.set(0, 42);
                    running.countDown();
                    try { finish.await(); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    // Retirement denies NEW writers, but an already pinned callback
                    // must complete its logically atomic multi-element update.
                    loan.set(1, 5);
                });
            } catch (Throwable failure) { writer.set(failure); }
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        region.close();
        assertThrows(IllegalStateException.class, region::snapshotAfterClose);
        finish.countDown();
        worker.join();
        assertNull(writer.get());
        int[] snapshot = region.snapshotAfterClose();
        assertEquals(42, snapshot[0]);
        assertEquals(5, snapshot[1]);
        assertThrows(IllegalStateException.class,
                () -> permit.withWrite(loan -> loan.set(0, 4)));
    }
}
