package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
final class HostSchedulerEntryGateTest {
    @Test
    void hostExclusionDefersGuestWithoutBlockingSubmittingCarrier() throws Exception {
        var gate = new HostSchedulerEntryGate(2);
        var dispatcher = Executors.newSingleThreadExecutor();
        var submitter = Executors.newSingleThreadExecutor();
        var executed = new CountDownLatch(1);
        var admissionError = new AtomicReference<Throwable>();
        try (var host = gate.enterHost()) {
            try {
                submitter.submit(() -> gate.submitGuest(
                        dispatcher, executed::countDown, admissionError::set))
                        .get(5, TimeUnit.SECONDS);
                assertEquals(1, gate.pendingGuests(),
                        "a carrier must enqueue, not wait for host Value.execute to exit");
                assertEquals(1L, executed.getCount());
            } finally {
                submitter.shutdownNow();
            }
        } finally {
            dispatcher.shutdown();
        }
        assertTrue(executed.await(5, TimeUnit.SECONDS));
        assertTrue(dispatcher.awaitTermination(5, TimeUnit.SECONDS),
                "a completion signal is not proof that the guest carrier left");
        assertNull(admissionError.get());
        gate.sealAdmission();
        assertTrue(gate.physicallyQuiescent());
    }

    @Test
    void hostCannotEnterBeforeActualGuestExit() throws Exception {
        var gate = new HostSchedulerEntryGate(2);
        var pool = Executors.newFixedThreadPool(2);
        var entered = new CountDownLatch(1);
        var releaseGuest = new CountDownLatch(1);
        var tryingHost = new CountDownLatch(1);
        var acquiredHost = new CountDownLatch(1);
        try {
            gate.submitGuest(pool, () -> {
                entered.countDown();
                try {
                    if (!releaseGuest.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release guest");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }, error -> fail("unexpected guest admission error", error));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var host = pool.submit(() -> {
                tryingHost.countDown();
                try (var ignored = gate.enterHost()) {
                    acquiredHost.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            assertTrue(tryingHost.await(5, TimeUnit.SECONDS));
            assertEquals(1L, acquiredHost.getCount(),
                    "host cannot claim context while a guest physically owns it");
            releaseGuest.countDown();
            host.get(5, TimeUnit.SECONDS);
            assertEquals(0L, acquiredHost.getCount());
        } finally {
            releaseGuest.countDown();
            pool.shutdownNow();
        }
        gate.sealAdmission();
        assertTrue(gate.physicallyQuiescent());
    }

    @Test
    void boundedQueueRejectsOverflowAndNestedHostScopesCannotReleaseEarly()
            throws Exception {
        var gate = new HostSchedulerEntryGate(1);
        var executed = new AtomicInteger();
        var errors = new AtomicReference<Throwable>();
        var outer = gate.enterHost();
        var inner = gate.enterHost();
        try {
            gate.submitGuest(Runnable::run, executed::incrementAndGet, errors::set);
            assertThrows(RejectedExecutionException.class,
                    () -> gate.submitGuest(Runnable::run, () -> { }, errors::set));
            inner.close();
            assertEquals(0, executed.get(), "nested host must not release parent");
            assertEquals(1, gate.pendingGuests());
        } finally {
            inner.close();
            outer.close();
        }
        assertEquals(1, executed.get());
        assertNull(errors.get());
        gate.sealAdmission();
        assertTrue(gate.physicallyQuiescent());
    }

    @Test
    void rejectedExecutorSettlesAdmissionAndCannotLeaveGhostPermit()
            throws Exception {
        var gate = new HostSchedulerEntryGate(1);
        var rejection = new AtomicReference<Throwable>();
        gate.submitGuest(job -> { throw new RejectedExecutionException("pool closed"); },
                () -> fail("rejected work ran"), rejection::set);
        assertInstanceOf(RejectedExecutionException.class, rejection.get());
        gate.sealAdmission();
        assertTrue(gate.physicallyQuiescent());
    }

    @Test
    void inlineGuestFailureReleasesExactlyOnePhysicalLease() {
        var gate = new HostSchedulerEntryGate(1);
        var admissionFailure = new AtomicReference<Throwable>();
        var guestFailure = new IllegalStateException("guest threw");
        assertSame(guestFailure, assertThrows(IllegalStateException.class,
                () -> gate.submitGuest(Runnable::run, () -> {
                    throw guestFailure;
                }, admissionFailure::set)));
        assertNull(admissionFailure.get(),
                "a guest-body failure is not an executor admission rejection");
        gate.sealAdmission();
        assertTrue(gate.physicallyQuiescent(),
                "inline execution cannot double-release the guest reservation");
    }

    @Test
    void sealedGateDrainsPreviouslyAuthorizedQueuedCleanupButRejectsNewWork()
            throws Exception {
        var gate = new HostSchedulerEntryGate(1);
        var ran = new AtomicInteger();
        try (var host = gate.enterHost()) {
            gate.submitGuest(Runnable::run, ran::incrementAndGet,
                    error -> fail("queued work rejected", error));
            gate.sealAdmission();
            assertFalse(gate.physicallyQuiescent());
            assertThrows(RejectedExecutionException.class,
                    () -> gate.submitGuest(Runnable::run, () -> { }, error -> { }));
            assertThrows(RejectedExecutionException.class, gate::enterHost);
        }
        assertEquals(1, ran.get());
        assertTrue(gate.physicallyQuiescent());
    }

    @Test
    void foreignThreadCannotReleaseHostPermit() throws Exception {
        var gate = new HostSchedulerEntryGate(1);
        var owner = gate.enterHost();
        var pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() ->
                assertThrows(SecurityException.class, owner::close)
            ).get(5, TimeUnit.SECONDS);
            assertEquals(0, gate.pendingGuests());
        } finally {
            pool.shutdownNow();
            owner.close();
        }
        gate.sealAdmission();
        assertTrue(gate.physicallyQuiescent());
    }
}
