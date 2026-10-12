package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic retirement state machine tests requiring no Graal image. */
final class VerifiedCloseFenceTest {
    @Test
    void twoConcurrentClosersWaitForOnePhysicalTeardown() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        VerifiedCloseFence fence = new VerifiedCloseFence(() -> {
            closes.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test interrupted");
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() ->
                    fence.awaitConfirmedClose("test Context"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CountDownLatch secondEntered = new CountDownLatch(1);
            Future<?> second = executor.submit(() -> {
                secondEntered.countDown();
                fence.awaitConfirmedClose("test Context");
            });
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            assertEquals(1, closes.get());
            assertFalse(first.isDone(), "physical close has not returned");
            assertFalse(second.isDone(), "concurrent close must await confirmation");
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            fence.awaitConfirmedClose("test Context");
            assertEquals(1, closes.get(), "never close twice");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedTeardownNeverReportsSuccessOrRetriesAllocation() {
        AtomicInteger calls = new AtomicInteger();
        VerifiedCloseFence fence = new VerifiedCloseFence(() -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("untrusted guest internal secret");
        });
        for (int attempt = 0; attempt < 3; attempt++) {
            var error = assertThrows(IllegalStateException.class,
                    () -> fence.awaitConfirmedClose("isolated Context"));
            assertTrue(error.getMessage().contains("capacity remains reserved"));
            assertFalse(error.getMessage().contains("secret"));
            assertNull(error.getCause(), "guest failure must not leak via cause chain");
        }
        assertEquals(1, calls.get());
    }

    @Test
    void interruptionCannotTurnAStillClosingContextIntoAConfirmedClose()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        VerifiedCloseFence fence = new VerifiedCloseFence(() -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("closer interrupted");
            }
        });
        Thread physical = new Thread(() -> fence.awaitConfirmedClose("Context"));
        physical.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        AtomicInteger refused = new AtomicInteger();
        Thread other = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                fence.awaitConfirmedClose("Context");
            } catch (IllegalStateException error) {
                if (Thread.currentThread().isInterrupted()
                        && error.getMessage().contains("interrupted")) {
                    refused.incrementAndGet();
                }
            }
        });
        try {
            other.start();
            other.join(5000);
            assertFalse(other.isAlive());
            assertEquals(1, refused.get());
            assertTrue(physical.isAlive());
        } finally {
            release.countDown();
            physical.join(5000);
            other.join(5000);
        }
        assertFalse(physical.isAlive());
        fence.awaitConfirmedClose("Context");
    }
}
