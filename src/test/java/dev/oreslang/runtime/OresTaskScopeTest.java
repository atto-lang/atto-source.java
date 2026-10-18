package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresTaskScopeTest {
    @Test
    void cancellingAResultDoesNotProvePhysicalExitOrFinallyCompletion() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch finallyEntered = new CountDownLatch(1);
            CountDownLatch releaseFinally = new CountDownLatch(1);
            OresTask<Integer> task = runtime.submitTask(() -> {
                try {
                    started.countDown();
                    Thread.sleep(30_000);
                    return 4;
                } finally {
                    finallyEntered.countDown();
                    assertTrue(releaseFinally.await(2, TimeUnit.SECONDS));
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            OresFuture<Void> joined = task.cancelAndJoin();
            try {
                assertTrue(finallyEntered.await(2, TimeUnit.SECONDS));
                assertTrue(task.isDone()); // Result/cancel Future already settled.
                assertTrue(task.isCancelled());
                assertFalse(joined.isDone()); // But cleanup has not returned.
                assertFalse(task.isQuiescent());
            } finally {
                releaseFinally.countDown();
            }
            assertNull(AsyncRuntime.await(joined));
            assertTrue(task.isQuiescent());
        }
    }

    @Test
    void cancellingTheDoneObserverCannotCancelTheProducerOrOtherObservers() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            OresTask<Integer> task = runtime.submitTask(() -> {
                started.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
                return 12;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            OresFuture<Void> discardedObserver = task.getDone();
            OresFuture<Void> liveObserver = task.getDone();
            assertTrue(discardedObserver.cancel(true));
            assertFalse(task.isCancelled());
            assertFalse(liveObserver.isDone());
            release.countDown();
            assertEquals(12, AsyncRuntime.await(task.asFuture()));
            assertNull(AsyncRuntime.await(liveObserver));
            assertTrue(task.isQuiescent());
        }
    }

    @Test
    void parentScopeCancellationPropagatesDownButNotToUnrelatedScopes() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope unrelated = new OresTaskScope(runtime);
             OresTaskScope parent = new OresTaskScope(runtime)) {
            CountDownLatch started = new CountDownLatch(2);
            OresTaskScope child = parent.childScope();
            OresTask<Void> a = parent.submit(() -> {
                started.countDown();
                Thread.sleep(30_000);
                return null;
            });
            OresTask<Void> b = child.submit(() -> {
                started.countDown();
                Thread.sleep(30_000);
                return null;
            });
            OresTask<Integer> separate = unrelated.submit(() -> 8);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(parent.cancel());
            assertFalse(parent.cancel());
            assertTrue(parent.isCancelRequested());
            assertTrue(child.isCancelRequested());
            assertThrows(RejectedExecutionException.class,
                    () -> child.submit(() -> 1));
            parent.close();
            assertTrue(a.isCancelled());
            assertTrue(b.isCancelled());
            assertTrue(a.isQuiescent());
            assertTrue(b.isQuiescent());
            assertEquals(8, AsyncRuntime.await(separate.asFuture()));
            assertFalse(separate.isCancelled());
        }
    }

    @Test
    void discardedTaskStillReportsItsFailureToTheScopeSupervisor() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            CountDownLatch reported = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();
            IllegalStateException original = new IllegalStateException("failed child");
            scope.submitDetached(() -> { throw original; }, failure -> {
                observed.set(failure);
                reported.countDown();
            });
            assertTrue(reported.await(2, TimeUnit.SECONDS));
            assertSame(original, observed.get());
        }
    }

    @Test
    void actorOrTaskCarrierCannotBlockByClosingItsOwnScope() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresTask<Boolean> child = scope.submit(() ->
                    assertThrows(IllegalStateException.class, scope::close) != null);
            assertTrue(AsyncRuntime.await(child.asFuture()));
        }
    }
    @Test
    void blockedCancellationSubscriberCannotStarveSiblingRevocation() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch callbackEntered = new CountDownLatch(1);
            CountDownLatch releaseSubscriber = new CountDownLatch(1);
            OresTask<Void> first = scope.submit(() -> {
                started.countDown();
                Thread.sleep(30_000);
                return null;
            });
            OresTask<Void> second = scope.submit(() -> {
                started.countDown();
                Thread.sleep(30_000);
                return null;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            java.util.function.BiConsumer<Void, Throwable> subscriber = (value, failure) -> {
                callbackEntered.countDown();
                boolean interrupted = false;
                for (;;) {
                    try {
                        releaseSubscriber.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            };
            first.getAwaited().whenCompleteRuntime(subscriber);
            second.getAwaited().whenCompleteRuntime(subscriber);

            AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
            Thread cancelThread = Thread.ofVirtual().start(() -> {
                try { scope.cancel(); }
                catch (Throwable failure) { cancelFailure.set(failure); }
            });
            try {
                assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
                // With single-phase cancellation, the first subscriber
                // blocks and the second producer remains runnable.
                assertTrue(first.isCancelled());
                assertTrue(second.isCancelled());
            } finally {
                releaseSubscriber.countDown();
            }
            cancelThread.join();
            assertNull(cancelFailure.get());
            assertNull(AsyncRuntime.await(first.getDone()));
            assertNull(AsyncRuntime.await(second.getDone()));
        }
    }

    @Test
    void interruptedScopeCloseStillJoinsPhysicalTaskCleanup() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch cleaning = new CountDownLatch(1);
            CountDownLatch releaseCleanup = new CountDownLatch(1);
            CountDownLatch closeReturned = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean interruptRestored =
                    new java.util.concurrent.atomic.AtomicBoolean();
            OresTask<Void> task = scope.submit(() -> {
                try {
                    started.countDown();
                    Thread.sleep(30_000);
                } finally {
                    cleaning.countDown();
                    boolean interrupted = false;
                    for (;;) {
                        try {
                            releaseCleanup.await();
                            break;
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                }
                return null;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Thread closer = Thread.ofVirtual().start(() -> {
                Thread.currentThread().interrupt();
                try {
                    scope.close();
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                } catch (Throwable thrown) {
                    failure.set(thrown);
                } finally {
                    closeReturned.countDown();
                }
            });
            try {
                assertTrue(cleaning.await(2, TimeUnit.SECONDS));
                assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS));
                assertFalse(task.isQuiescent());
            } finally {
                releaseCleanup.countDown();
            }
            assertTrue(closeReturned.await(3, TimeUnit.SECONDS));
            closer.join();
            assertNull(failure.get());
            assertTrue(interruptRestored.get());
            assertTrue(task.isQuiescent());
        }
    }

}
