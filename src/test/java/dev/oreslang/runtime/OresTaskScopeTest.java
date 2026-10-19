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

    @Test
    void deepScopeTreeCancelsAndClosesWithoutRecursiveStackOverflow() {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            OresTaskScope root = new OresTaskScope(runtime);
            OresTaskScope leaf = root;
            for (int i = 0; i < 4_096; i++) {
                leaf = leaf.childScope();
            }
            assertTrue(root.cancel());
            assertTrue(leaf.isCancelRequested());
            assertDoesNotThrow(root::close);
        }
    }

    @Test
    void deferredFutureCancellationRevokesBeforeNotifyingSubscribers() {
        java.util.concurrent.atomic.AtomicInteger revocations =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger callbacks =
                new java.util.concurrent.atomic.AtomicInteger();
        OresFuture<Integer> future =
                new OresFuture<>(revocations::incrementAndGet);
        future.whenCompleteRuntime((value, failure) -> {
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure);
            callbacks.incrementAndGet();
        });
        Runnable deliver = future.cancelDeferredNotification();
        assertNotNull(deliver);
        assertTrue(future.isCancelled());
        assertEquals(1, revocations.get());
        assertEquals(0, callbacks.get());
        assertFalse(future.cancel(true));
        deliver.run();
        assertEquals(1, callbacks.get());
    }


    @Test
    void subscriberArrivingBetweenRevocationAndDeliveryCannotFireEarly() {
        java.util.concurrent.atomic.AtomicInteger callbackCount =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger hookCount =
                new java.util.concurrent.atomic.AtomicInteger();
        OresFuture<Integer> future = new OresFuture<>(hookCount::incrementAndGet);
        Runnable publish = future.cancelDeferredNotification();
        assertNotNull(publish);
        assertEquals(1, hookCount.get());
        assertTrue(future.isCancelled());

        // Late subscriber used to run immediately here, potentially blocking
        // scope cancellation before other children had been revoked.
        future.whenCompleteRuntime((value, failure) -> {
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure);
            callbackCount.incrementAndGet();
        });
        assertEquals(0, callbackCount.get());
        assertNull(future.runtimeTerminalStateOrNull(),
                "scheduler must not treat a deferred cancellation as ready");

        publish.run();
        assertEquals(1, callbackCount.get());
        assertNotNull(future.runtimeTerminalStateOrNull());
        publish.run(); // A duplicate deferred notifier must be idempotent.
        assertEquals(1, callbackCount.get());
    }


    @Test
    void interruptedConcurrentCloserCannotReturnBeforePhysicalCleanup() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch cleanupEntered = new CountDownLatch(1);
            CountDownLatch releaseCleanup = new CountDownLatch(1);
            CountDownLatch secondaryDone = new CountDownLatch(1);
            AtomicReference<Throwable> primaryFailure = new AtomicReference<>();
            AtomicReference<Throwable> secondaryFailure = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean interruptRestored =
                    new java.util.concurrent.atomic.AtomicBoolean();

            OresTask<Void> worker = scope.submit(() -> {
                try {
                    started.countDown();
                    Thread.sleep(30_000);
                } finally {
                    cleanupEntered.countDown();
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
            Thread first = Thread.ofVirtual().start(() -> {
                try { scope.close(); }
                catch (Throwable problem) { primaryFailure.set(problem); }
            });

            try {
                assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS));
                Thread second = Thread.ofVirtual().start(() -> {
                    Thread.currentThread().interrupt();
                    try {
                        scope.close();
                        interruptRestored.set(Thread.currentThread().isInterrupted());
                    } catch (Throwable problem) {
                        secondaryFailure.set(problem);
                    } finally {
                        secondaryDone.countDown();
                    }
                });
                assertFalse(secondaryDone.await(100, TimeUnit.MILLISECONDS),
                        "interrupted concurrent close must not bypass physical cleanup");
                releaseCleanup.countDown();
                second.join();
                assertNull(secondaryFailure.get());
                assertTrue(interruptRestored.get(),
                        "host thread interrupt must be restored after its join");
            } finally {
                releaseCleanup.countDown();
                first.join();
            }
            assertNull(primaryFailure.get());
            assertTrue(worker.isQuiescent());
            assertTrue(secondaryDone.getCount() == 0L);
        }
    }


    @Test
    void parentCancellationFinishesSubtreeWhenChildClaimedButNotTraversed()
            throws Exception {
        // Deterministically model the intermediate state of a competing
        // cancel(): a child has sealed its own admission gate but has not
        // reached its grandchildren. This interleaving is possible before
        // its cancellation walk snapshots and visits the descendants.
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope root = new OresTaskScope(runtime)) {
            OresTaskScope child = root.childScope();
            OresTaskScope grandchild = child.childScope();
            CountDownLatch entered = new CountDownLatch(1);
            OresTask<Void> live = grandchild.submit(() -> {
                entered.countDown();
                Thread.sleep(30_000);
                return null;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            java.lang.reflect.Field claimed =
                    OresTaskScope.class.getDeclaredField("cancelled");
            claimed.setAccessible(true);
            claimed.setBoolean(child, true);
            assertFalse(grandchild.isCancelRequested());
            assertTrue(root.cancel());
            assertTrue(child.isCancelRequested());
            assertTrue(grandchild.isCancelRequested(),
                    "a claimed-but-untraversed child must not hide grandchildren");
            assertTrue(live.isCancelled());
            assertFalse(root.cancel(), "repeated cancellation remains idempotent");
            root.close();
            assertTrue(live.isQuiescent());
        }
    }


    @Test
    void hostTaskAdmissionRacingScopeCloseNeverLosesPhysicalJoin() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            for (int trial = 0; trial < 64; trial++) {
                OresTaskScope scope = new OresTaskScope(runtime);
                CountDownLatch begin = new CountDownLatch(1);
                AtomicReference<OresTask<Integer>> accepted = new AtomicReference<>();
                AtomicReference<Throwable> submitFailure = new AtomicReference<>();
                AtomicReference<Throwable> closeFailure = new AtomicReference<>();
                Thread starter = Thread.ofVirtual().start(() -> {
                    try {
                        assertTrue(begin.await(3, TimeUnit.SECONDS));
                        accepted.set(scope.submit(() -> 43));
                    } catch (RejectedExecutionException expected) {
                        // The close winner denies new host Task admissions.
                    } catch (Throwable failure) {
                        submitFailure.set(failure);
                    }
                });
                Thread closer = Thread.ofVirtual().start(() -> {
                    try {
                        assertTrue(begin.await(3, TimeUnit.SECONDS));
                        scope.close();
                    } catch (Throwable failure) {
                        closeFailure.set(failure);
                    }
                });
                begin.countDown();
                starter.join(4_000);
                closer.join(4_000);
                assertFalse(starter.isAlive(), "host admission stuck on " + trial);
                assertFalse(closer.isAlive(), "scope close stuck on " + trial);
                assertNull(submitFailure.get(), "unexpected host rejection on " + trial);
                assertNull(closeFailure.get(), "unexpected close failure on " + trial);
                OresTask<Integer> task = accepted.get();
                if (task != null) {
                    assertTrue(task.isQuiescent(),
                            "returned host Task must physically exit before close");
                }
            }
        }
    }

    @Test
    void registrationDetachFailureCannotStrandDescendantCancellation() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope root = new OresTaskScope(runtime)) {
            OresTaskScope child = root.childScope();
            OresTaskScope grandchild = child.childScope();
            CountDownLatch started = new CountDownLatch(1);
            OresTask<Integer> live = grandchild.submit(() -> {
                started.countDown();
                Thread.sleep(30_000);
                return 3;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            java.lang.reflect.Field field =
                    OresTaskScope.class.getDeclaredField("externalCancellation");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<OresCancellationToken.Registration> registrations =
                    (java.util.List<OresCancellationToken.Registration>) field.get(root);
            java.util.concurrent.atomic.AtomicInteger detached =
                    new java.util.concurrent.atomic.AtomicInteger();
            registrations.add(() -> {
                detached.incrementAndGet();
                throw new IllegalStateException("injected detach failure");
            });

            IllegalStateException failure =
                    assertThrows(IllegalStateException.class, root::cancel);
            assertTrue(failure.getMessage().contains("injected detach failure"));
            assertEquals(1, detached.get());
            assertTrue(child.isCancelRequested());
            assertTrue(grandchild.isCancelRequested());
            assertTrue(live.isCancelled(),
                    "all Task producers must be revoked even if detach fails");
            assertDoesNotThrow(root::close);
            assertTrue(live.isQuiescent());
            assertEquals(1, detached.get(), "failed detach must not be retried twice");
        }
    }

    @Test
    void cancelledScopeDoesNotEnterPendingHostTaskTurn() throws Exception {
        CountDownLatch turnScheduled = new CountDownLatch(1);
        CountDownLatch allowTurn = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger entered =
                new java.util.concurrent.atomic.AtomicInteger();
        try (AsyncRuntime runtime = new AsyncRuntime(turn -> {
                 turnScheduled.countDown();
                 boolean interrupted = false;
                 try {
                     for (;;) {
                         try {
                             allowTurn.await();
                             break;
                         } catch (InterruptedException ignored) {
                             interrupted = true;
                         }
                     }
                     turn.run();
                 } finally {
                     if (interrupted) Thread.currentThread().interrupt();
                 }
             });
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresTask<Integer> child = scope.submit(() -> {
                entered.incrementAndGet();
                return 17;
            });
            assertTrue(turnScheduled.await(2, TimeUnit.SECONDS));
            try {
                assertTrue(scope.cancel());
            } finally {
                allowTurn.countDown();
            }
            assertNull(AsyncRuntime.await(child.getDone()));
            assertEquals(0, entered.get(),
                    "worker must not enter cancelled scope after admission");
        } finally {
            allowTurn.countDown();
        }
    }
}
