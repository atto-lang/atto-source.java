package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class OresCarrierPermitPoolTest {

    @Test void borrowerCannotConsumeAnotherDomainsProtectedReserve() {
        var permits = new OresCarrierPermitPool(8);
        var held = new ArrayList<OresCarrierPermitPool.Permit>();
        try {
            for (int i = 0; i < 4; i++) {
                var permit = permits.tryAcquire(OresCarrierPermitPool.Domain.CONTROL);
                assertNotNull(permit, "CONTROL should use its reserve plus three spare permits");
                held.add(permit);
            }
            assertNull(permits.tryAcquire(OresCarrierPermitPool.Domain.CONTROL),
                    "four unmet domain reserves must remain protected");

            for (var domain : List.of(
                    OresCarrierPermitPool.Domain.PRIVATE,
                    OresCarrierPermitPool.Domain.SHARED,
                    OresCarrierPermitPool.Domain.UNTRUSTED,
                    OresCarrierPermitPool.Domain.LIFECYCLE)) {
                var permit = permits.tryAcquire(domain);
                assertNotNull(permit, "protected reserve unavailable for " + domain);
                held.add(permit);
            }
            assertEquals(8, permits.totalHeld());
            assertNull(permits.tryAcquire(OresCarrierPermitPool.Domain.CONTROL));
        } finally {
            held.forEach(OresCarrierPermitPool.Permit::close);
        }
        assertEquals(0, permits.totalHeld());
    }

    @Test void abandonedNewThreadReservationReconcilesExactlyOnce() {
        var permits = new OresCarrierPermitPool(5);
        OresCarrierPermitPool.ReconciliableThreadFactory factory =
                permits.threadFactory(
                        OresCarrierPermitPool.Domain.CONTROL, "permit-abandoned-");

        Thread abandoned = factory.newThread(() -> { });
        assertNotNull(abandoned);
        assertEquals(Thread.State.NEW, abandoned.getState());
        assertEquals(1, permits.totalHeld(),
                "factory reserves before JDK thread-container startup");

        factory.reconcileCreatedThreads();
        assertEquals(0, permits.totalHeld(),
                "abandoned NEW worker must return its reservation");
        factory.reconcileCreatedThreads();
        assertEquals(0, permits.totalHeld(),
                "reconciliation must be idempotent");
    }

    @Test void adaptiveExecutorHoldsPermitForPhysicalWorkerLifetime() throws Exception {
        var permits = new OresCarrierPermitPool(5);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondRan = new CountDownLatch(1);

        try (var workers = new AdaptiveCarrierExecutor(
                0,
                1,
                8,
                permits.threadFactory(
                        OresCarrierPermitPool.Domain.CONTROL, "permit-control-"))) {
            assertEquals(0, permits.totalHeld());
            workers.execute(() -> {
                entered.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, permits.totalHeld());
            assertEquals(1, permits.held(OresCarrierPermitPool.Domain.CONTROL));

            // No second physical worker is allowed by this executor, but ready
            // work remains queueable behind the permitted worker.
            workers.execute(secondRan::countDown);
            assertEquals(1, permits.totalHeld());
            release.countDown();
            assertTrue(secondRan.await(2, TimeUnit.SECONDS));
        }
        assertEquals(0, permits.totalHeld(),
                "executor worker-loop exit must return the permit exactly once");
    }

    @Test void thrownWorkerStillReturnsPermitAfterExecutorRetiresIt() throws Exception {
        var permits = new OresCarrierPermitPool(5);
        var ran = new CountDownLatch(1);
        try (var workers = new AdaptiveCarrierExecutor(
                0,
                1,
                8,
                permits.threadFactory(
                        OresCarrierPermitPool.Domain.PRIVATE, "permit-throw-"))) {
            workers.execute(() -> {
                ran.countDown();
                throw new AssertionError("synthetic worker failure");
            });
            assertTrue(ran.await(2, TimeUnit.SECONDS));
        }
        assertEquals(0, permits.totalHeld());
        assertEquals(0, permits.held(OresCarrierPermitPool.Domain.PRIVATE));
    }

    @Test void concurrentAcquireReleaseNeverExceedsMaximum() throws Exception {
        var permits = new OresCarrierPermitPool(16);
        var start = new CountDownLatch(1);
        var threads = new ArrayList<Thread>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var domains = OresCarrierPermitPool.Domain.values();

        for (int index = 0; index < 12; index++) {
            final int seed = index;
            Thread thread = Thread.ofPlatform().daemon(true).unstarted(() -> {
                var random = new java.util.Random(seed);
                var owned = new ArrayList<OresCarrierPermitPool.Permit>();
                try {
                    start.await();
                    for (int i = 0; i < 10_000; i++) {
                        if (!owned.isEmpty() && random.nextBoolean()) {
                            owned.remove(owned.size() - 1).close();
                        } else {
                            var permit = permits.tryAcquire(
                                    domains[random.nextInt(domains.length)]);
                            if (permit != null) owned.add(permit);
                        }
                        assertTrue(permits.totalHeld() <= permits.maximum());
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    owned.forEach(OresCarrierPermitPool.Permit::close);
                }
            });
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
            assertFalse(thread.isAlive(), "permit stress worker did not terminate");
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(0, permits.totalHeld());
    }
}
