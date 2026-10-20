package dev.oreslang.runtime;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Ordinary virtual-carrier Task physical-exit fences must include trusted
 * scope ownership retirement. Host Future callbacks isolate exceptions and
 * cannot be the authority for reporting successful resource release.
 */
@Timeout(20)
final class OresTaskOrdinaryRetirementTest {
    @Test
    void brokenScopeRemovalFailsOrdinaryPhysicalFence() throws Exception {
        var runtime = new AsyncRuntime();
        var scope = new OresTaskScope(runtime);
        Field ownerField = OresTaskScope.class.getDeclaredField("tasks");
        ownerField.setAccessible(true);
        Object original = ownerField.get(scope);
        var removalAttempts = new AtomicInteger();
        Set<Object> broken = new HashSet<>() {
            @Override public boolean remove(Object value) {
                removalAttempts.incrementAndGet();
                throw new IllegalStateException("injected ordinary owner failure");
            }
        };
        ownerField.set(scope, broken);
        try {
            OresTask<Integer> task = scope.submit(() -> 19);
            assertEquals(19, AsyncRuntime.await(task.getAwaited()));
            var failure = assertThrows(IllegalStateException.class,
                    () -> AsyncRuntime.await(task.getDone()));
            assertTrue(failure.toString().contains("injected ordinary owner failure"));
            assertEquals(1, removalAttempts.get(), "retire owner exactly once");
            assertFalse(scope.physicallyQuiescent(),
                    "a failed retirement must retain its owned slot");
            assertThrows(IllegalStateException.class, scope::close);
        } finally {
            ownerField.set(scope, original);
            try { scope.close(); } catch (IllegalStateException expected) { }
            runtime.close();
        }
    }

    @Test
    void silentOwnerRemovalRefusalFailsPhysicalFence() throws Exception {
        var runtime = new AsyncRuntime();
        var scope = new OresTaskScope(runtime);
        Field ownerField = OresTaskScope.class.getDeclaredField("tasks");
        ownerField.setAccessible(true);
        Object original = ownerField.get(scope);
        Set<Object> broken = new HashSet<>() {
            @Override public boolean remove(Object value) { return false; }
        };
        ownerField.set(scope, broken);
        try {
            OresTask<Integer> task = scope.submit(() -> 23);
            assertEquals(23, AsyncRuntime.await(task.getAwaited()));
            var failure = assertThrows(IllegalStateException.class,
                    () -> AsyncRuntime.await(task.getDone()));
            assertTrue(failure.toString().contains("ordinary Task owner slot missing"));
            assertFalse(scope.physicallyQuiescent());
            assertThrows(IllegalStateException.class, scope::close);
        } finally {
            ownerField.set(scope, original);
            try { scope.close(); } catch (IllegalStateException expected) { }
            runtime.close();
        }
    }

    @Test
    void sealedScopeRejectsWithoutAttemptingNeverRegisteredOwnerRemoval()
            throws Exception {
        try (var runtime = new AsyncRuntime();
             var scope = new OresTaskScope(runtime)) {
            scope.cancel();
            Field field = OresTaskScope.class.getDeclaredField("tasks");
            field.setAccessible(true);
            Object original = field.get(scope);
            AtomicInteger removalAttempts = new AtomicInteger();
            Set<Object> corruptOnRemove = new HashSet<>() {
                @Override public boolean remove(Object slot) {
                    removalAttempts.incrementAndGet();
                    throw new IllegalStateException("unregistered slot was removed");
                }
            };
            field.set(scope, corruptOnRemove);
            try {
                assertThrows(RejectedExecutionException.class,
                        () -> scope.submit(() -> 3));
                assertEquals(0, removalAttempts.get(),
                        "a sealed scope never registered this provisional owner");
                assertTrue(scope.physicallyQuiescent());
            } finally {
                field.set(scope, original);
            }
        }
    }

    @Test
    void insertThenThrowReservationDoesNotStrandAnUnboundOwner() throws Exception {
        var runtime = new AsyncRuntime();
        var scope = new OresTaskScope(runtime);
        Field ownerField = OresTaskScope.class.getDeclaredField("tasks");
        ownerField.setAccessible(true);
        Object original = ownerField.get(scope);
        Set<Object> hostile = new HashSet<>() {
            @Override public boolean add(Object value) {
                super.add(value);
                throw new IllegalStateException("injected post-insert failure");
            }
        };
        ownerField.set(scope, hostile);
        var executed = new AtomicInteger();
        try {
            var failure = assertThrows(IllegalStateException.class,
                    () -> scope.submit(() -> executed.incrementAndGet()));
            assertTrue(failure.getMessage().contains("post-insert failure"));
            assertEquals(0, executed.get(), "no guest Task can escape reservation");
            assertTrue(scope.physicallyQuiescent(),
                    "aborted owner must be physically retired before returning");
            scope.close();
        } finally {
            ownerField.set(scope, original);
            scope.close();
            runtime.close();
        }
    }

    @Test
    void runtimeAwaitTaskExitDoesNotSkipPendingTrustedRetirement()
            throws Exception {
        try (var runtime = new AsyncRuntime()) {
            CountDownLatch inRetirement = new CountDownLatch(1);
            CountDownLatch allowRetirement = new CountDownLatch(1);
            CountDownLatch joined = new CountDownLatch(1);
            var admission = runtime.<Integer>prepareTaskAdmission();
            admission.installRetirement(() -> {
                inRetirement.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        allowRetirement.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            });
            Thread waiter = null;
            try {
                runtime.submitPreparedTask(admission, () -> 43);
                assertEquals(43, AsyncRuntime.await(admission.owner.getAwaited()));
                assertTrue(inRetirement.await(3, TimeUnit.SECONDS));
                waiter = Thread.ofVirtual().start(() -> {
                    runtime.awaitTaskExit(admission.owner.getAwaited());
                    joined.countDown();
                });
                assertFalse(joined.await(100, TimeUnit.MILLISECONDS),
                        "map removal before retirement allowed an early join");
            } finally {
                allowRetirement.countDown();
                if (waiter != null) waiter.join(3000);
            }
            assertEquals(0L, joined.getCount());
            assertNull(AsyncRuntime.await(admission.owner.getDone()));
        }
    }

    @Test
    void physicalExitWaitsUntilTrustedRetirementCallbackFinishes() throws Exception {
        try (var runtime = new AsyncRuntime()) {
            var enteredRetirement = new CountDownLatch(1);
            var allowRetirement = new CountDownLatch(1);
            var admission = runtime.<Integer>prepareTaskAdmission();
            admission.installRetirement(() -> {
                enteredRetirement.countDown();
                boolean interrupted = false;
                for (;;) {
                    try {
                        allowRetirement.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            });
            try {
                runtime.submitPreparedTask(admission, () -> 37);
                assertEquals(37, AsyncRuntime.await(admission.owner.getAwaited()));
                assertTrue(enteredRetirement.await(3, TimeUnit.SECONDS));
                assertFalse(admission.owner.getDone().isDone(),
                        "result settlement is not trusted physical retirement");
                assertFalse(admission.owner.isQuiescent());
            } finally {
                allowRetirement.countDown();
            }
            assertNull(AsyncRuntime.await(admission.owner.getDone()));
            assertTrue(admission.owner.isQuiescent());
        }
    }

    @Test
    void concurrentExecutorRejectionAndShutdownRetirePreparedOwnerOnce()
            throws Exception {
        final int rounds = 200;
        for (int i = 0; i < rounds; i++) {
            AsyncRuntime runtime = new AsyncRuntime();
            var admission = runtime.<Integer>prepareTaskAdmission();
            AtomicInteger releases = new AtomicInteger();
            AtomicReference<Throwable> admissionError = new AtomicReference<>();
            AtomicReference<Throwable> shutdownError = new AtomicReference<>();
            CountDownLatch start = new CountDownLatch(1);
            admission.installRetirement(releases::incrementAndGet);
            Thread submitter = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    runtime.submitPreparedTask(admission, () -> 1);
                } catch (RejectedExecutionException acceptedRejection) {
                    // A close that wins before executor admission may reject.
                } catch (Throwable failure) {
                    admissionError.set(failure);
                }
            });
            Thread closer = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    runtime.close();
                } catch (Throwable failure) {
                    shutdownError.set(failure);
                }
            });
            start.countDown();
            try {
                submitter.join(3000);
                closer.join(3000);
                assertFalse(submitter.isAlive(), "submitter hung at round " + i);
                assertFalse(closer.isAlive(), "closer hung at round " + i);
                assertNull(admissionError.get(), String.valueOf(admissionError.get()));
                assertNull(shutdownError.get(), String.valueOf(shutdownError.get()));
                assertNull(AsyncRuntime.await(admission.owner.getDone()));
                assertEquals(1, releases.get(),
                        "rejection/cancellation must retire once at round " + i);
            } finally {
                runtime.close();
            }
        }
    }

    @Test
    void preDispatchRejectionRetiresPreparedExitExactlyOnce() {
        var runtime = new AsyncRuntime();
        var admission = runtime.<Integer>prepareTaskAdmission();
        var retirements = new AtomicInteger();
        admission.installRetirement(retirements::incrementAndGet);
        runtime.close();
        assertThrows(RejectedExecutionException.class,
                () -> runtime.submitPreparedTask(admission, () -> 31));
        assertNull(AsyncRuntime.await(admission.owner.getDone()));
        assertEquals(1, retirements.get());
        assertThrows(IllegalStateException.class,
                () -> runtime.submitPreparedTask(admission, () -> 32));
        assertEquals(1, retirements.get());
    }

    @Test
    void rejectedRetirementCallbackFailsPreparedExit() {
        var runtime = new AsyncRuntime();
        var admission = runtime.<Integer>prepareTaskAdmission();
        var retirements = new AtomicInteger();
        admission.installRetirement(() -> {
            retirements.incrementAndGet();
            throw new IllegalStateException("injected pre-dispatch release failure");
        });
        runtime.close();
        assertThrows(RejectedExecutionException.class,
                () -> runtime.submitPreparedTask(admission, () -> 33));
        var failure = assertThrows(IllegalStateException.class,
                () -> AsyncRuntime.await(admission.owner.getDone()));
        assertTrue(failure.toString().contains("injected pre-dispatch release failure"));
        assertEquals(1, retirements.get());
    }
}
