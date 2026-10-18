package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class OresCancellationSourceTest {
    @Test
    void linkedCancellationIsOneWayAndOrComposed() {
        try (OresCancellationSource first = new OresCancellationSource();
             OresCancellationSource second = new OresCancellationSource();
             OresCancellationSource linked =
                     OresCancellationSource.linked(first.token(), second.token())) {
            AtomicInteger callbacks = new AtomicInteger();
            linked.token().onCancellationRequested(callbacks::incrementAndGet);
            assertFalse(linked.isCancellationRequested());
            assertTrue(second.cancel());
            assertTrue(linked.isCancellationRequested());
            assertFalse(first.isCancellationRequested());
            assertEquals(1, callbacks.get());
            assertFalse(linked.cancel());
            assertEquals(1, callbacks.get());
        }
    }

    @Test
    void cancellingChildDetachesParentObserversWithoutCancellingParent() {
        try (OresCancellationSource parent = new OresCancellationSource();
             OresCancellationSource linked =
                     OresCancellationSource.linked(parent.token())) {
            assertEquals(1, parent.notification().pendingRuntimeWaiterCount());
            assertTrue(linked.cancel());
            assertEquals(0, parent.notification().pendingRuntimeWaiterCount());
            assertFalse(parent.isCancellationRequested());
        }
    }

    @Test
    void observerDetachDoesNotCancelSourceAndLateObserversFireOnce() {
        try (OresCancellationSource source = new OresCancellationSource()) {
            AtomicInteger detached = new AtomicInteger();
            OresCancellationToken.Registration subscription =
                    source.token().onCancellationRequested(detached::incrementAndGet);
            subscription.close();
            subscription.close();
            assertTrue(source.cancel());
            assertEquals(0, detached.get());

            AtomicInteger late = new AtomicInteger();
            source.token().onCancellationRequested(late::incrementAndGet);
            assertEquals(1, late.get());
            assertFalse(source.cancel());
            assertEquals(1, late.get());
        }
    }

    @Test
    void externalTokenCancelsOwningScopeAndDescendantsNotItsSource() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresCancellationSource external = new OresCancellationSource();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            scope.cancelWhen(external.token());
            OresTaskScope childScope = scope.childScope();
            CountDownLatch entered = new CountDownLatch(1);
            OresTask<Void> child = childScope.submit(() -> {
                entered.countDown();
                Thread.sleep(30_000);
                return null;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(external.cancel());
            scope.close();
            assertTrue(scope.isCancelRequested());
            assertTrue(childScope.isCancelRequested());
            assertTrue(child.isCancelled());
            assertTrue(child.isQuiescent());
        }
    }

    @Test
    void cancellingScopeNeverCancelsUpstreamSourceAndDetachesIt() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresCancellationSource upstream = new OresCancellationSource();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            scope.cancelWhen(upstream.token());
            assertEquals(1, upstream.notification().pendingRuntimeWaiterCount());
            assertTrue(scope.cancel());
            assertFalse(upstream.isCancellationRequested());
            assertEquals(0, upstream.notification().pendingRuntimeWaiterCount());
            assertThrows(RejectedExecutionException.class,
                    () -> scope.cancelWhen(upstream.token()));
        }
    }

    @Test
    void alreadyCancelledSourceCancelsNewScopeImmediately() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresCancellationSource upstream = new OresCancellationSource();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            assertTrue(upstream.cancel());
            scope.cancelWhen(upstream.token());
            assertTrue(scope.isCancelRequested());
            assertEquals(0, upstream.notification().pendingRuntimeWaiterCount());
        }
    }

    @Test
    void concurrentUpstreamCancellationsPublishOneTerminalEvent() throws Exception {
        for (int iteration = 0; iteration < 64; iteration++) {
            try (OresCancellationSource first = new OresCancellationSource();
                 OresCancellationSource second = new OresCancellationSource();
                 OresCancellationSource linked =
                         OresCancellationSource.linked(first.token(), second.token())) {
                AtomicInteger deliveries = new AtomicInteger();
                linked.token().onCancellationRequested(deliveries::incrementAndGet);
                CountDownLatch fire = new CountDownLatch(1);
                Thread left = Thread.ofVirtual().start(() -> {
                    try {
                        fire.await();
                        first.cancel();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                });
                Thread right = Thread.ofVirtual().start(() -> {
                    try {
                        fire.await();
                        second.cancel();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                });
                fire.countDown();
                left.join();
                right.join();
                assertTrue(linked.isCancellationRequested());
                assertEquals(1, deliveries.get());
                assertEquals(0, first.notification().pendingRuntimeWaiterCount());
                assertEquals(0, second.notification().pendingRuntimeWaiterCount());
            }
        }
    }


    @Test
    void deepLinkedCancellationUsesBoundedJavaStackAndDetachesAllUpstreams() {
        final int depth = 8_192;
        java.util.ArrayList<OresCancellationSource> sources =
                new java.util.ArrayList<>(depth + 1);
        OresCancellationSource root = new OresCancellationSource();
        sources.add(root);
        try {
            OresCancellationSource previous = root;
            for (int i = 0; i < depth; i++) {
                previous = OresCancellationSource.linked(previous.token());
                sources.add(previous);
            }
            AtomicInteger leafDelivery = new AtomicInteger();
            previous.token().onCancellationRequested(leafDelivery::incrementAndGet);
            assertTrue(root.cancel());
            assertEquals(1, leafDelivery.get());
            for (OresCancellationSource current : sources) {
                assertTrue(current.isCancellationRequested());
                assertEquals(0, current.notification().pendingRuntimeWaiterCount());
            }
            assertFalse(root.cancel());
        } finally {
            for (int i = sources.size() - 1; i >= 0; i--) {
                sources.get(i).close();
            }
        }
    }

    @Test
    void diamondLinkedCancellationIsIdempotentWithoutCancellingUnrelatedSource() {
        try (OresCancellationSource root = new OresCancellationSource();
             OresCancellationSource unrelated = new OresCancellationSource();
             OresCancellationSource left = OresCancellationSource.linked(root.token());
             OresCancellationSource right = OresCancellationSource.linked(root.token());
             OresCancellationSource child =
                     OresCancellationSource.linked(left.token(), right.token())) {
            AtomicInteger deliveries = new AtomicInteger();
            child.token().onCancellationRequested(deliveries::incrementAndGet);
            assertTrue(root.cancel());
            assertTrue(left.isCancellationRequested());
            assertTrue(right.isCancellationRequested());
            assertTrue(child.isCancellationRequested());
            assertFalse(unrelated.isCancellationRequested());
            assertEquals(1, deliveries.get());
            assertEquals(0, left.notification().pendingRuntimeWaiterCount());
            assertEquals(0, right.notification().pendingRuntimeWaiterCount());
        }
    }



    @Test
    void blockedSiblingObserverDoesNotDelayTransitiveRevocation() throws Exception {
        try (OresCancellationSource root = new OresCancellationSource();
             OresCancellationSource first = OresCancellationSource.linked(root.token());
             OresCancellationSource second = OresCancellationSource.linked(root.token())) {
            CountDownLatch callbackEntered = new CountDownLatch(1);
            CountDownLatch releaseCallback = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> failed =
                    new java.util.concurrent.atomic.AtomicReference<>();
            AtomicInteger secondCallbacks = new AtomicInteger();
            first.token().onCancellationRequested(() -> {
                callbackEntered.countDown();
                try {
                    releaseCallback.await();
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                }
            });
            second.token().onCancellationRequested(secondCallbacks::incrementAndGet);

            Thread canceller = Thread.ofVirtual().start(() -> {
                try {
                    root.cancel();
                } catch (Throwable failure) {
                    failed.set(failure);
                } finally {
                    finished.countDown();
                }
            });
            try {
                assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
                assertTrue(first.isCancellationRequested());
                assertTrue(second.isCancellationRequested(),
                        "sibling must be revoked before blocking host callback");
                assertFalse(finished.await(100, TimeUnit.MILLISECONDS));
            } finally {
                releaseCallback.countDown();
                canceller.join();
            }
            assertNull(failed.get());
            assertEquals(1, secondCallbacks.get());
            assertEquals(0, root.notification().pendingRuntimeWaiterCount());
        }
    }

    @Test
    void throwingListenerCannotSuppressCancellationOfOtherLinkedChildren() {
        try (OresCancellationSource root = new OresCancellationSource();
             OresCancellationSource first = OresCancellationSource.linked(root.token());
             OresCancellationSource second = OresCancellationSource.linked(root.token())) {
            AtomicInteger delivered = new AtomicInteger();
            first.token().onCancellationRequested(() -> {
                throw new IllegalStateException("simulated observer error");
            });
            first.token().onCancellationRequested(delivered::incrementAndGet);
            second.token().onCancellationRequested(delivered::incrementAndGet);
            assertTrue(root.cancel());
            assertTrue(first.isCancellationRequested());
            assertTrue(second.isCancellationRequested());
            assertEquals(2, delivered.get());
        }
    }


    @Test
    void reentrantTokenObserversQueueNewCancellationWaves() {
        try (OresCancellationSource root = new OresCancellationSource();
             OresCancellationSource unrelated = new OresCancellationSource()) {
            AtomicInteger rootNotices = new AtomicInteger();
            AtomicInteger unrelatedNotices = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<OresCancellationSource> lateChild =
                    new java.util.concurrent.atomic.AtomicReference<>();
            root.token().onCancellationRequested(() -> {
                rootNotices.incrementAndGet();
                assertFalse(root.cancel(), "reentrant claim must lose");
                assertTrue(unrelated.cancel(), "independent source is claimed");
                OresCancellationSource child =
                        OresCancellationSource.linked(root.token());
                assertTrue(child.isCancellationRequested());
                lateChild.set(child);
            });
            unrelated.token().onCancellationRequested(unrelatedNotices::incrementAndGet);
            assertTrue(root.cancel());
            assertEquals(1, rootNotices.get());
            assertEquals(1, unrelatedNotices.get());
            OresCancellationSource child = lateChild.get();
            assertNotNull(child);
            try (child) {
                assertTrue(child.isCancellationRequested());
                assertEquals(0, child.notification().pendingRuntimeWaiterCount());
            }
        }
    }

    @Test
    void detachedLinkedChildIsNotRetainedByItsFormerParent() {
        try (OresCancellationSource root = new OresCancellationSource();
             OresCancellationSource child = OresCancellationSource.linked(root.token())) {
            assertFalse(root.isCancellationRequested());
            assertTrue(child.cancel());
            assertTrue(root.cancel());
            assertFalse(child.cancel());
            assertTrue(child.isCancellationRequested());
            assertEquals(0, root.notification().pendingRuntimeWaiterCount());
        }
    }

}
