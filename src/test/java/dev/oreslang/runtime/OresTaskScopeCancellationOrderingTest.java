package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Guards against parent cancellation hooks reentering an unsealed child. */
@Timeout(15)
final class OresTaskScopeCancellationOrderingTest {
    @SuppressWarnings("unchecked")
    private static void ownSyntheticTask(
            OresTaskScope scope, OresFuture<Integer> completion)
            throws Exception {
        // Canonical scope owns a pre-reserved slot, not the Task reference.
        // A synthetic fixture must obey the same ownership representation.
        Class<?> slotType = Class.forName(
                "dev.oreslang.runtime.OresTaskScope$OwnershipSlot");
        var constructor = slotType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object slot = constructor.newInstance();
        Field task = slotType.getDeclaredField("task");
        task.setAccessible(true);
        task.set(slot, new OresTask<>(
                completion, OresFuture.completed(null)));
        Field tasks = OresTaskScope.class.getDeclaredField("tasks");
        tasks.setAccessible(true);
        ((Set<Object>) tasks.get(scope)).add(slot);
    }

    @Test
    void deferredFutureCancellationRevokesBeforeDeliveringOldAndLateWaiters() {
        var revoked = new java.util.concurrent.atomic.AtomicInteger();
        var delivered = new java.util.concurrent.atomic.AtomicInteger();
        var future = new OresFuture<Integer>(revoked::incrementAndGet);

        future.whenCompleteRuntime((ignored, failure) -> {
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure);
            assertEquals(1, revoked.get(), "producer must be revoked first");
            delivered.incrementAndGet();
        });

        Runnable notify = future.cancelDeferredNotification();
        assertNotNull(notify);
        assertTrue(future.isCancelled());
        assertEquals(1, revoked.get());
        assertEquals(0, delivered.get(), "early subscriber must be deferred");
        future.whenCompleteRuntime((ignored, failure) -> {
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure);
            assertEquals(1, revoked.get());
            delivered.incrementAndGet();
        });
        assertEquals(0, delivered.get(), "late subscriber must also be deferred");

        notify.run();
        assertEquals(2, delivered.get());
        notify.run();
        assertEquals(2, delivered.get(), "delivery must be single-shot");
        assertNull(future.cancelDeferredNotification(),
                "terminal cancellation cannot mint another notification");
    }

    @Test
    void concurrentCancellationCannotDeliverEarlyOrLateSubscriberBeforeProducerRevocation()
            throws Exception {
        var hookEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseHook = new java.util.concurrent.CountDownLatch(1);
        var hookRuns = new java.util.concurrent.atomic.AtomicInteger();
        var observerCalls = new java.util.concurrent.atomic.AtomicInteger();
        var failed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var future = new OresFuture<Integer>(() -> {
            hookRuns.incrementAndGet();
            hookEntered.countDown();
            try {
                if (!releaseHook.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("producer revocation timed out");
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interruption);
            }
        });
        future.whenCompleteRuntime((ignored, failure) -> {
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure);
            assertEquals(1, hookRuns.get());
            observerCalls.incrementAndGet();
        });
        Thread canceller = Thread.ofPlatform().start(() -> {
            try {
                assertTrue(future.cancel(false));
            } catch (Throwable problem) {
                failed.compareAndSet(null, problem);
            }
        });
        try {
            assertTrue(hookEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(future.isCancelled());
            assertNull(future.runtimeTerminalStateOrNull(),
                    "runtime consumers must await completion of producer revocation");
            future.whenCompleteRuntime((ignored, failure) -> {
                assertInstanceOf(java.util.concurrent.CancellationException.class, failure);
                observerCalls.incrementAndGet();
            });
            assertEquals(0, observerCalls.get(),
                    "a late subscriber must not bypass the cancellation barrier");
        } finally {
            releaseHook.countDown();
            canceller.join(7000);
        }
        assertFalse(canceller.isAlive());
        assertNull(failed.get());
        assertNotNull(future.runtimeTerminalStateOrNull());
        assertEquals(1, hookRuns.get(), "revocation must run exactly once");
        assertEquals(2, observerCalls.get(), "both observers must run once after revocation");
    }

    @Test
    void parentCancellationHookCannotCreateTaskInGrandchild() throws Exception {
        try (var runtime = new AsyncRuntime();
             var root = new OresTaskScope(runtime)) {
            var child = root.childScope();
            var grandchild = child.childScope();
            var sealed = new AtomicBoolean();
            var future = new OresFuture<Integer>(() -> {
                assertTrue(child.isCancelRequested());
                assertTrue(grandchild.isCancelRequested());
                assertThrows(RejectedExecutionException.class,
                        () -> grandchild.submit(() -> 1));
                sealed.set(true);
            });
            ownSyntheticTask(root, future);
            assertTrue(root.cancel());
            assertTrue(future.isCancelled());
            assertTrue(sealed.get(), "descendant admission must close before parent hook");
            assertFalse(root.cancel());
        }
    }

    @Test
    void parentFutureListenersSeeAllDescendantProducersRevoked()
            throws Exception {
        try (var runtime = new AsyncRuntime();
             var root = new OresTaskScope(runtime)) {
            var child = root.childScope();
            var descendant = child.childScope();
            var parentFuture = new OresFuture<Integer>();
            var childFuture = new OresFuture<Integer>();
            var observer = new AtomicBoolean();
            ownSyntheticTask(root, parentFuture);
            ownSyntheticTask(descendant, childFuture);
            parentFuture.whenCompleteRuntime((ignored, failure) -> {
                assertTrue(descendant.isCancelRequested());
                assertTrue(childFuture.isCancelled(),
                        "descendant revocation must precede parent subscriber");
                observer.set(true);
            });
            assertTrue(root.cancel());
            assertTrue(childFuture.isCancelled());
            assertTrue(observer.get());
        }
    }

    @Test
    void failedPreflightDoesNotPartiallySealTaskTree() throws Exception {
        try (var runtime = new AsyncRuntime();
             var root = new OresTaskScope(runtime)) {
            var child = root.childScope();
            var grandchild = child.childScope();
            Field field = OresTaskScope.class.getDeclaredField("tasks");
            field.setAccessible(true);
            Object original = field.get(grandchild);
            field.set(grandchild, new java.util.HashSet<Object>() {
                @Override public java.util.Iterator<Object> iterator() {
                    throw new OutOfMemoryError("injected deep Task cancellation snapshot");
                }
            });
            try {
                assertThrows(OutOfMemoryError.class, root::cancel);
                assertFalse(root.isCancelRequested());
                assertFalse(child.isCancelRequested());
                assertFalse(grandchild.isCancelRequested());
                assertNotNull(grandchild.childScope());
                assertThrows(OutOfMemoryError.class, root::close,
                        "close may not join until its complete cancellation plan commits");
                assertFalse(root.isCancelRequested());
            } finally {
                field.set(grandchild, original);
            }
            root.close();
            assertTrue(root.isCancelRequested());
            assertTrue(grandchild.isCancelRequested());
        }
    }

    @Test
    void concurrentChildAdmissionWaitsForAtomicPreflightAndSeal() throws Exception {
        try (var runtime = new AsyncRuntime();
             var root = new OresTaskScope(runtime)) {
            var child = root.childScope();
            var grandchild = child.childScope();
            var observed = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            Field field = OresTaskScope.class.getDeclaredField("tasks");
            field.setAccessible(true);
            Object original = field.get(grandchild);
            field.set(grandchild, new java.util.HashSet<Object>() {
                @Override public java.util.Iterator<Object> iterator() {
                    observed.countDown();
                    try {
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            throw new AssertionError("cancellation preflight release timed out");
                    } catch (InterruptedException problem) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(problem);
                    }
                    return super.iterator();
                }
            });
            var closeError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var admitError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var admitted = new java.util.concurrent.atomic.AtomicBoolean();
            Thread cancel = Thread.ofPlatform().start(() -> {
                try {
                    root.cancel();
                } catch (Throwable problem) {
                    closeError.set(problem);
                }
            });
            Thread contender = null;
            try {
                assertTrue(observed.await(5, java.util.concurrent.TimeUnit.SECONDS));
                contender = Thread.ofPlatform().start(() -> {
                    try {
                        grandchild.childScope();
                        admitted.set(true);
                    } catch (Throwable problem) {
                        admitError.set(problem);
                    }
                });
                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (contender.getState() != Thread.State.BLOCKED
                        && admitError.get() == null && !admitted.get()
                        && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertEquals(Thread.State.BLOCKED, contender.getState());
            } finally {
                release.countDown();
                cancel.join(7000);
                if (contender != null) contender.join(7000);
                field.set(grandchild, original);
            }
            assertFalse(cancel.isAlive());
            if (contender != null) assertFalse(contender.isAlive());
            assertNull(closeError.get());
            assertFalse(admitted.get());
            assertInstanceOf(RejectedExecutionException.class, admitError.get());
        }
    }

    @Test
    void closeSnapshotAllocationFailureDoesNotStrandJoinOwnership()
            throws Exception {
        try (var runtime = new AsyncRuntime()) {
            var root = new OresTaskScope(runtime);
            Field field = OresTaskScope.class.getDeclaredField("tasks");
            field.setAccessible(true);
            Object original = field.get(root);
            var iterations = new java.util.concurrent.atomic.AtomicInteger();
            field.set(root, new java.util.HashSet<Object>() {
                @Override public java.util.Iterator<Object> iterator() {
                    if (iterations.incrementAndGet() == 2)
                        throw new OutOfMemoryError("injected close frame snapshot OOME");
                    return super.iterator();
                }
            });
            try {
                assertThrows(OutOfMemoryError.class, root::close);
                assertTrue(root.isCancelRequested());
            } finally {
                field.set(root, original);
            }
            root.close();
        }
    }

}
