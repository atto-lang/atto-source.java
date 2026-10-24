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

    @Test void workConservingCapacityKeepsAllFiveDomainReservesAcrossFullRange() {
        var domains = OresCarrierPermitPool.Domain.values();
        for (int maximum = 5; maximum <= 256; maximum++) {
            var pool = new OresCarrierPermitPool(maximum);
            var borrowed = new ArrayList<OresCarrierPermitPool.Permit>();
            try {
                // CONTROL is permitted to borrow all surplus, but never the
                // protected admission of the other four execution domains.
                for (int i = 0; i < maximum - 4; i++) {
                    var permit = pool.tryAcquire(OresCarrierPermitPool.Domain.CONTROL);
                    assertNotNull(permit, "failed borrowing at ceiling " + maximum);
                    borrowed.add(permit);
                }
                assertNull(pool.tryAcquire(OresCarrierPermitPool.Domain.CONTROL));
                for (var domain : domains) {
                    if (domain == OresCarrierPermitPool.Domain.CONTROL) continue;
                    var permit = pool.tryAcquire(domain);
                    assertNotNull(permit, "lost a reserved domain at ceiling " + maximum);
                    borrowed.add(permit);
                }
                assertEquals(maximum, pool.snapshot().totalHeld());
                assertNull(pool.tryAcquire(OresCarrierPermitPool.Domain.UNTRUSTED));
                var exact = pool.snapshot();
                assertEquals(maximum, exact.maximum());
                assertEquals(0, exact.free());
                assertEquals(1, exact.privateHeld());
                assertEquals(1, exact.sharedHeld());
                assertEquals(1, exact.untrustedHeld());
                assertEquals(1, exact.lifecycleHeld());
            } finally {
                borrowed.forEach(OresCarrierPermitPool.Permit::close);
            }
            assertEquals(0, pool.totalHeld(), "permit leaked at ceiling " + maximum);
        }
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

    @Test void reconciledAbandonedThreadCannotExecuteIfStartedLater() throws Exception {
        var permits = new OresCarrierPermitPool(5);
        OresCarrierPermitPool.ReconciliableThreadFactory factory =
                permits.threadFactory(
                        OresCarrierPermitPool.Domain.PRIVATE, "permit-late-start-");
        var ran = new CountDownLatch(1);

        Thread abandoned = factory.newThread(ran::countDown);
        assertNotNull(abandoned);
        assertEquals(1, permits.totalHeld());

        factory.reconcileCreatedThreads();
        assertEquals(0, permits.totalHeld());

        abandoned.start();
        abandoned.join(3_000L);
        assertFalse(abandoned.isAlive());
        assertEquals(1L, ran.getCount(),
                "a worker whose permit was reconciled away must never execute runtime work");
        assertEquals(0, permits.totalHeld());
        assertTrue(factory.awaitPhysicalTermination(1, TimeUnit.SECONDS));
    }

    @Test void decoratorFailureReturnsReservedPermitImmediately() {
        var permits = new OresCarrierPermitPool(5);
        OresCarrierPermitPool.ReconciliableThreadFactory factory =
                permits.threadFactory(
                        OresCarrierPermitPool.Domain.LIFECYCLE,
                        "permit-decorator-failure-",
                        ignored -> {
                            throw new IllegalStateException("synthetic decorator failure");
                        });

        assertThrows(IllegalStateException.class, () -> factory.newThread(() -> { }));
        assertEquals(0, permits.totalHeld(),
                "failed worker decoration must not retain a physical carrier permit");
        assertEquals(0, permits.held(OresCarrierPermitPool.Domain.LIFECYCLE));
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

    @Test void reserveDeniedGrowthQueuesOnExistingCarrierInsteadOfLosingWork()
            throws Exception {
        var permits = new OresCarrierPermitPool(5);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queuedRan = new CountDownLatch(1);

        var workers = new AdaptiveCarrierExecutor(
                0,
                4,
                8,
                permits.threadFactory(
                        OresCarrierPermitPool.Domain.SHARED, "permit-shared-"));
        try {
            workers.execute(() -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, permits.held(OresCarrierPermitPool.Domain.SHARED));

            assertDoesNotThrow(() -> workers.execute(queuedRan::countDown),
                    "reserve-protected carrier growth must degrade to queueing");
            assertFalse(queuedRan.await(100, TimeUnit.MILLISECONDS),
                    "queued turn must wait for the existing carrier");
            var pressure = permits.snapshot();
            assertEquals(1L, pressure.deniedReserveProtection());
            assertEquals(0L, pressure.deniedCapacity());
            assertEquals(1, pressure.sharedHeld());

            release.countDown();
            assertTrue(queuedRan.await(2, TimeUnit.SECONDS),
                    "existing carrier must eventually execute reserve-protected queued work");
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
        assertEquals(0, permits.totalHeld());
    }

    @Test void denialReasonsAndTransactionalRollbackRemainReconciled() {
        var permits = new OresCarrierPermitPool(8);
        var first = permits.tryAcquire(OresCarrierPermitPool.Domain.CONTROL);
        assertNotNull(first);
        try {
            assertNull(permits.tryAcquireMany(OresCarrierPermitPool.Domain.CONTROL, 4),
                    "transaction must fail when its final permit would steal a reserve");
            var afterRollback = permits.snapshot();
            assertEquals(1, afterRollback.totalHeld());
            assertEquals(1, afterRollback.controlHeld());
            assertEquals(1L, afterRollback.deniedReserveProtection());
            assertEquals(0L, afterRollback.deniedCapacity());

            var held = new ArrayList<OresCarrierPermitPool.Permit>();
            try {
                for (var domain : List.of(
                        OresCarrierPermitPool.Domain.PRIVATE,
                        OresCarrierPermitPool.Domain.SHARED,
                        OresCarrierPermitPool.Domain.UNTRUSTED,
                        OresCarrierPermitPool.Domain.LIFECYCLE)) {
                    var permit = permits.tryAcquire(domain);
                    assertNotNull(permit);
                    held.add(permit);
                }
                while (permits.totalHeld() < permits.maximum()) {
                    var permit = permits.tryAcquire(OresCarrierPermitPool.Domain.PRIVATE);
                    assertNotNull(permit);
                    held.add(permit);
                }
                assertNull(permits.tryAcquire(OresCarrierPermitPool.Domain.SHARED));
                var full = permits.snapshot();
                assertEquals(8, full.totalHeld());
                assertEquals(0, full.free());
                assertEquals(1L, full.deniedCapacity());
                assertEquals(
                        full.controlHeld() + full.privateHeld() + full.sharedHeld()
                                + full.untrustedHeld() + full.lifecycleHeld(),
                        full.totalHeld());
            } finally {
                held.forEach(OresCarrierPermitPool.Permit::close);
            }
        } finally {
            first.close();
            first.close();
        }
        assertEquals(0, permits.totalHeld(),
                "transaction rollback and idempotent close must leave no permit leak");
    }

    @Test void ordinaryTaskFailureDoesNotRetirePermitOwningWorkerOrStrandQueue()
            throws Exception {
        var permits = new OresCarrierPermitPool(5);
        var failedTurnRan = new CountDownLatch(1);
        var queuedTurnRan = new CountDownLatch(1);
        var releaseQueuedTurn = new CountDownLatch(1);
        var firstCarrier = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var secondCarrier = new java.util.concurrent.atomic.AtomicReference<Thread>();

        var workers = new AdaptiveCarrierExecutor(
                0,
                1,
                8,
                permits.threadFactory(
                        OresCarrierPermitPool.Domain.PRIVATE, "permit-failure-"));
        try {
            workers.execute(() -> {
                firstCarrier.set(Thread.currentThread());
                failedTurnRan.countDown();
                throw new AssertionError("synthetic ordinary task failure");
            });
            workers.execute(() -> {
                secondCarrier.set(Thread.currentThread());
                queuedTurnRan.countDown();
                try {
                    releaseQueuedTurn.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });

            assertTrue(failedTurnRan.await(2, TimeUnit.SECONDS));
            assertTrue(queuedTurnRan.await(2, TimeUnit.SECONDS),
                    "ordinary task failure must not strand queued work");
            assertSame(firstCarrier.get(), secondCarrier.get(),
                    "contained ordinary failure must preserve the physical carrier");
            assertEquals(1, permits.held(OresCarrierPermitPool.Domain.PRIVATE));
            assertEquals(1, workers.startedWorkerCount());
        } finally {
            releaseQueuedTurn.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
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
