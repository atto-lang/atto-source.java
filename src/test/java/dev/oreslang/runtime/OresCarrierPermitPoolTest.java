package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
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

    @Test void javaThreadConsumesPermitOnlyWhenPhysicalStartBegins() throws Exception {
        var permits = new OresCarrierPermitPool(5);
        ThreadFactory control = permits.threadFactory(
                OresCarrierPermitPool.Domain.CONTROL, "permit-control-");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        Thread first = control.newThread(() -> {
            entered.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertEquals(0, permits.totalHeld(),
                "allocating a Java Thread object is not physical carrier admission");

        first.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertEquals(1, permits.totalHeld());
        assertEquals(1, permits.held(OresCarrierPermitPool.Domain.CONTROL));

        Thread denied = control.newThread(() -> { });
        assertThrows(RejectedExecutionException.class, denied::start,
                "cap=5 leaves no spare permit while four other reserves are unmet");
        assertEquals(1, permits.totalHeld(),
                "denied Thread.start must not leak a physical permit");

        release.countDown();
        first.join(2_000);
        assertFalse(first.isAlive());
        assertEquals(0, permits.totalHeld(),
                "worker-loop exit must return the permit exactly once");
    }

    @Test void thrownWorkerStillReturnsPermit() throws Exception {
        var permits = new OresCarrierPermitPool(5);
        Thread worker = permits.threadFactory(
                        OresCarrierPermitPool.Domain.PRIVATE, "permit-throw-")
                .newThread(() -> { throw new AssertionError("synthetic worker failure"); });
        worker.setUncaughtExceptionHandler((thread, failure) -> { });
        worker.start();
        worker.join(2_000);
        assertFalse(worker.isAlive());
        assertEquals(0, permits.totalHeld());
        assertEquals(0, permits.held(OresCarrierPermitPool.Domain.PRIVATE));
    }

    @Test void denialReasonsAndSnapshotReconcileExactly() {
        var permits = new OresCarrierPermitPool(5);
        var held = new ArrayList<OresCarrierPermitPool.Permit>();
        try {
            held.add(permits.tryAcquire(OresCarrierPermitPool.Domain.CONTROL));
            assertNotNull(held.get(0));
            assertNull(permits.tryAcquire(OresCarrierPermitPool.Domain.CONTROL),
                    "second CONTROL carrier must preserve four unmet reserves");

            for (var domain : List.of(
                    OresCarrierPermitPool.Domain.PRIVATE,
                    OresCarrierPermitPool.Domain.SHARED,
                    OresCarrierPermitPool.Domain.UNTRUSTED,
                    OresCarrierPermitPool.Domain.LIFECYCLE)) {
                var permit = permits.tryAcquire(domain);
                assertNotNull(permit);
                held.add(permit);
            }
            assertNull(permits.tryAcquire(OresCarrierPermitPool.Domain.PRIVATE),
                    "fully held global ceiling must report hard capacity denial");

            var snapshot = permits.snapshot();
            assertEquals(5, snapshot.maximum());
            assertEquals(5, snapshot.totalHeld());
            assertEquals(0, snapshot.free());
            assertEquals(1, snapshot.controlHeld());
            assertEquals(1, snapshot.privateHeld());
            assertEquals(1, snapshot.sharedHeld());
            assertEquals(1, snapshot.untrustedHeld());
            assertEquals(1, snapshot.lifecycleHeld());
            assertEquals(1L, snapshot.deniedReserveProtection());
            assertEquals(1L, snapshot.deniedCapacity());
        } finally {
            held.forEach(OresCarrierPermitPool.Permit::close);
        }
        var released = permits.snapshot();
        assertEquals(0, released.totalHeld());
        assertEquals(5, released.free());
        assertEquals(1L, released.deniedReserveProtection());
        assertEquals(1L, released.deniedCapacity());
    }

    @Test void multiAcquireRollbackDoesNotLeakPartialPermits() {
        var permits = new OresCarrierPermitPool(8);
        var control = permits.tryAcquire(OresCarrierPermitPool.Domain.CONTROL);
        assertNotNull(control);
        try {
            assertNull(permits.tryAcquireMany(OresCarrierPermitPool.Domain.CONTROL, 4),
                    "transaction must fail when its final permit would steal a reserve");
            var snapshot = permits.snapshot();
            assertEquals(1, snapshot.totalHeld(),
                    "partial transaction must restore original held state");
            assertEquals(1, snapshot.controlHeld());
            assertEquals(7, snapshot.free());
            assertEquals(1L, snapshot.deniedReserveProtection());
            assertEquals(0L, snapshot.deniedCapacity());
        } finally {
            control.close();
            control.close();
        }
        assertEquals(0, permits.totalHeld(),
                "Permit.close must remain idempotent after transactional pressure");
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
