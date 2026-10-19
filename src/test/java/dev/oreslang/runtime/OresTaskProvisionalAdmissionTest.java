package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Host-only admission/cancellation proofs; not general guest Task lowering. */
final class OresTaskProvisionalAdmissionTest {
    @Test
    void cancellationBeforeCompletionAttachmentIsReplayed() {
        var physical = new OresFuture<Void>();
        var provisional = OresTask.<Integer>provisional(physical);
        AtomicInteger notified = new AtomicInteger();
        AtomicReference<Throwable> callbackViolation = new AtomicReference<>();
        var completion = new OresFuture<Integer>();
        completion.whenCompleteRuntime((value, failure) -> {
            // OresFuture deliberately isolates host waiter failures. An
            // assertion thrown INSIDE this callback is swallowed, so record
            // the violation and assert it from the test thread instead.
            if (Thread.holdsLock(provisional)) {
                callbackViolation.compareAndSet(null,
                        new AssertionError("completion listener ran under shell monitor"));
            }
            notified.incrementAndGet();
        });

        assertNull(provisional.cancelDeferredNotification(),
                "unbound shell records cancellation but has no Future to revoke");
        provisional.attachCompletion(completion);
        assertTrue(completion.isCancelled(),
                "cancellation must survive the provisional admission window");
        assertEquals(1, notified.get());
        assertNull(callbackViolation.get(),
                String.valueOf(callbackViolation.get()));
        assertNull(provisional.cancelDeferredNotification(),
                "duplicate cancellation must not deliver subscribers twice");
        assertEquals(1, notified.get());
    }

    @Test
    void cancellingWhileExecutorAdmissionIsBlockedDoesNotHoldScopeLocks()
            throws Exception {
        CountDownLatch dispatchEntered = new CountDownLatch(1);
        CountDownLatch permitDispatch = new CountDownLatch(1);
        AtomicReference<Throwable> admittingFailure = new AtomicReference<>();
        AtomicReference<Throwable> closingFailure = new AtomicReference<>();
        AtomicReference<OresTask<Integer>> admitted = new AtomicReference<>();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger enteredGuest = new AtomicInteger();
        CountDownLatch cancellationReturned = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
        var scheduler = OresScheduler.runtimeOwned(
                "admission-race-contract", 1, turn -> {
                    dispatchEntered.countDown();
                    try {
                        if (!permitDispatch.await(5, TimeUnit.SECONDS)) {
                            throw new RejectedExecutionException(
                                    "timed out waiting for admission gate release");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RejectedExecutionException(
                                "admission interrupted", interrupted);
                    }
                    Thread.ofVirtual().start(turn);
                });

        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            Thread admitting = Thread.ofVirtual().start(() -> {
                try {
                    admitted.set(scope.startCompilerFrame(scheduler,
                            resume -> {
                                enteredGuest.incrementAndGet();
                                return new OresScheduler.Done<>(7);
                            },
                            releases::incrementAndGet));
                } catch (Throwable failure) {
                    admittingFailure.set(failure);
                }
            });
            assertTrue(dispatchEntered.await(2, TimeUnit.SECONDS),
                    "scheduler dispatch must reach the deliberately blocked executor");

            Thread cancelling = Thread.ofVirtual().start(() -> {
                try { scope.cancel(); }
                catch (Throwable failure) { cancelFailure.set(failure); }
                finally { cancellationReturned.countDown(); }
            });
            try {
                assertTrue(cancellationReturned.await(2, TimeUnit.SECONDS),
                        "parent cancellation must not wait for executor admission");
                assertNull(cancelFailure.get(), String.valueOf(cancelFailure.get()));
                Thread closer = Thread.ofVirtual().start(() -> {
                    try { scope.close(); }
                    catch (Throwable failure) { closingFailure.set(failure); }
                    finally { closeReturned.countDown(); }
                });
                assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS),
                        "close must not escape before provisional child finishes admission");
                permitDispatch.countDown();
                admitting.join();
                assertNull(admittingFailure.get(), String.valueOf(admittingFailure.get()));
                assertNotNull(admitted.get());
                assertTrue(closeReturned.await(5, TimeUnit.SECONDS),
                        "scope close must eventually join the final physical fence");
                closer.join();
                assertNull(closingFailure.get(), String.valueOf(closingFailure.get()));
                assertTrue(admitted.get().isQuiescent());
                assertEquals(0, enteredGuest.get(),
                        "cancelled provisional child must never enter guest source");
                assertEquals(1, releases.get(), "generation lease released exactly once");
            } finally {
                permitDispatch.countDown();
                cancelling.join();
                admitting.join();
            }
        } finally {
            scheduler.close();
        }
    }

    @Test
    void admissionFailureReleasesLeaseOutsideScopeLocksExactlyOnce()
            throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime);
             OresScheduler scheduler = new OresScheduler(1)) {
            java.lang.reflect.Field gateField =
                    OresTaskScope.class.getDeclaredField("gate");
            gateField.setAccessible(true);
            java.lang.reflect.Field treeField =
                    OresTaskScope.class.getDeclaredField("treeAdmissionGate");
            treeField.setAccessible(true);
            Object gate = gateField.get(scope);
            Object tree = treeField.get(scope);
            scheduler.close();
            AtomicInteger releases = new AtomicInteger();
            assertThrows(RejectedExecutionException.class,
                    () -> scope.startCompilerFrame(scheduler,
                            resume -> new OresScheduler.Done<>(7), () -> {
                                assertFalse(Thread.holdsLock(gate));
                                assertFalse(Thread.holdsLock(tree));
                                releases.incrementAndGet();
                            }));
            assertEquals(1, releases.get());
            assertTrue(scope.physicallyQuiescent());
        }
    }
}
