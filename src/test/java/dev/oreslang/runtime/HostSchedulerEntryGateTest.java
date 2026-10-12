package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CancellationException;
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
    void acceptingExecutorThatThrowsCannotRunGuestLater() {
        var gate = new HostSchedulerEntryGate(1);
        var escaped = new AtomicReference<Runnable>();
        var guestRuns = new AtomicInteger();
        var failures = new AtomicInteger();
        gate.submitGuest(task -> {
                    escaped.set(task);
                    throw new RejectedExecutionException("accepted then reported failure");
                }, guestRuns::incrementAndGet, error -> {
                    assertInstanceOf(RejectedExecutionException.class, error);
                    failures.incrementAndGet();
                });
        gate.sealAdmission();
        assertTrue(gate.physicallyQuiescent());
        assertNotNull(escaped.get());
        escaped.get().run();
        assertEquals(0, guestRuns.get(), "a late accepted task must not enter guest code");
        assertEquals(1, failures.get(), "admission failure is delivered exactly once");
        assertTrue(gate.physicallyQuiescent());
    }

    @Test
    void executorThrowAfterGuestStartsDoesNotRevokePhysicalLease() throws Exception {
        var gate = new HostSchedulerEntryGate(1);
        var pool = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failureCallbacks = new AtomicInteger();
        try {
            assertThrows(RejectedExecutionException.class,
                    () -> gate.submitGuest(task -> {
                        pool.execute(task);
                        try {
                            assertTrue(entered.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                        throw new RejectedExecutionException("executor threw after start");
                    }, () -> {
                        entered.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }, error -> failureCallbacks.incrementAndGet()));
            gate.sealAdmission();
            assertFalse(gate.physicallyQuiescent(),
                    "a running guest retains its physical lease even after executor throws");
        } finally {
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(0, failureCallbacks.get(),
                "a started task is never reported as an unstarted admission rejection");
        assertTrue(gate.physicallyQuiescent());
    }

    @Test
    void duplicateExecutorRunnableExecutesGuestBodyAtMostOnce() {
        var gate = new HostSchedulerEntryGate(1);
        var runs = new AtomicInteger();
        gate.submitGuest(task -> {
            task.run();
            task.run();
        }, runs::incrementAndGet, error -> fail("unexpected rejection", error));
        gate.sealAdmission();
        assertEquals(1, runs.get());
        assertTrue(gate.physicallyQuiescent(),
                "duplicate invocation cannot double release the entry lease");
    }

    @Test
    void queuedCancellationPreventsExecutionAndNotifiesExactlyOnce() throws Exception {
        var gate = new HostSchedulerEntryGate(2);
        var guestRuns = new AtomicInteger();
        var failed = new AtomicInteger();
        try (var host = gate.enterHost()) {
            var ticket = gate.submitGuest(Runnable::run, guestRuns::incrementAndGet,
                    error -> {
                        assertInstanceOf(CancellationException.class, error);
                        failed.incrementAndGet();
                    });
            assertEquals(1, gate.pendingGuests());
            assertTrue(ticket.cancelBeforeStart());
            assertFalse(ticket.cancelBeforeStart(), "ticket cancellation must be one-use");
            assertEquals(0, gate.pendingGuests());
            assertEquals(0, guestRuns.get());
            gate.sealAdmission();
        }
        assertEquals(1, failed.get());
        assertEquals(0, guestRuns.get());
        assertTrue(gate.physicallyQuiescent());
    }

    @Test
    void acceptedWorkCanBeRevokedUntilItsActualExecutionStarts() {
        var gate = new HostSchedulerEntryGate(1);
        var delayed = new AtomicReference<Runnable>();
        var guestRuns = new AtomicInteger();
        var failed = new AtomicInteger();
        var ticket = gate.submitGuest(delayed::set, guestRuns::incrementAndGet,
                error -> {
                    assertInstanceOf(CancellationException.class, error);
                    failed.incrementAndGet();
                });
        assertNotNull(delayed.get());
        gate.sealAdmission();
        assertFalse(gate.physicallyQuiescent(),
                "accepted but unexecuted work retains its admission reservation");
        assertTrue(ticket.cancelBeforeStart());
        assertTrue(gate.physicallyQuiescent());
        delayed.get().run();
        assertEquals(0, guestRuns.get());
        assertEquals(1, failed.get());
        assertTrue(gate.physicallyQuiescent(), "late executor replay does not release twice");
    }

    @Test
    void runningGuestCannotBeCancelledAsIfItNeverEntered() throws Exception {
        var gate = new HostSchedulerEntryGate(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var ticket = gate.submitGuest(pool, () -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } finally {
                    done.countDown();
                }
            }, error -> fail("running guest must not be pre-start cancelled", error));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            gate.sealAdmission();
            assertFalse(ticket.cancelBeforeStart());
            assertFalse(gate.physicallyQuiescent());
            release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(gate.physicallyQuiescent());
    }

    @Test
    void queuedInlineFailureAndRejectionDoNotStrandLaterReservations()
            throws Exception {
        var gate = new HostSchedulerEntryGate(3);
        var firstFailure = new IllegalArgumentException("guest failure");
        var callbackFailure = new IllegalStateException("callback failure");
        var laterRuns = new AtomicInteger();
        var lastRuns = new AtomicInteger();
        var host = gate.enterHost();
        try {
            gate.submitGuest(Runnable::run, () -> { throw firstFailure; },
                    error -> fail("inline guest failure is not admission rejection", error));
            gate.submitGuest(Runnable::run, laterRuns::incrementAndGet,
                    error -> fail("second guest rejected", error));
            gate.submitGuest(task -> {
                throw new RejectedExecutionException("executor not accepting");
            }, () -> fail("rejected guest ran"), error -> {
                assertInstanceOf(RejectedExecutionException.class, error);
                lastRuns.incrementAndGet();
                throw callbackFailure;
            });
            gate.sealAdmission();
            var failure = assertThrows(IllegalArgumentException.class, host::close);
            assertSame(firstFailure, failure);
            assertEquals(1, failure.getSuppressed().length);
            assertSame(callbackFailure, failure.getSuppressed()[0]);
        } finally {
            host.close();
        }
        assertEquals(1, laterRuns.get(),
                "failure of an earlier queued task cannot strand later tickets");
        assertEquals(1, lastRuns.get());
        assertTrue(gate.physicallyQuiescent());
    }

    @Test
    void sealingPreventsNestedHostReentryButAllowsOriginalLeaseToClose()
            throws Exception {
        var gate = new HostSchedulerEntryGate(1);
        try (var owner = gate.enterHost()) {
            gate.sealAdmission();
            assertThrows(RejectedExecutionException.class, gate::enterHost);
            assertFalse(gate.physicallyQuiescent());
        }
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
