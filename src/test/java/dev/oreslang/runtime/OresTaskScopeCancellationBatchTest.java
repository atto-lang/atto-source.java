package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic cancellation ordering probes. The synthetic owner records
 * let a test block an INTERNAL producer hook without parking a guest carrier
 * or depending on scheduling order of AsyncRuntime worker threads. They use
 * the production Task/Future cancellation methods; no guest code is enabled.
 */
final class OresTaskScopeCancellationBatchTest {
    private static void injectOwned(
            OresTaskScope owner, OresFuture<Void> completion) throws Exception {
        Class<?> slotClass = Arrays.stream(OresTaskScope.class.getDeclaredClasses())
                .filter(kind -> kind.getSimpleName().equals("OwnershipSlot"))
                .findFirst().orElseThrow();
        Constructor<?> constructor = slotClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object slot = constructor.newInstance();
        Field taskField = slotClass.getDeclaredField("task");
        taskField.setAccessible(true);
        taskField.set(slot, new OresTask<>(completion, OresFuture.completed(null)));
        Field ownedField = OresTaskScope.class.getDeclaredField("tasks");
        ownedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Object> owned = (Set<Object>) ownedField.get(owner);
        assertTrue(owned.add(slot));
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @Test
    void overlappingParentAndChildCannotNotifyBeforeBothRevoke() throws Exception {
        OresTaskScope root = OresTaskScope.compilerOwned();
        OresTaskScope child = root.childScope();
        CountDownLatch childRevocationEntered = new CountDownLatch(1);
        CountDownLatch releaseChildRevocation = new CountDownLatch(1);
        CountDownLatch rootRevoked = new CountDownLatch(1);
        CountDownLatch rootCallback = new CountDownLatch(1);
        AtomicBoolean childHookFinished = new AtomicBoolean();
        AtomicBoolean subscriberSawRevokedChild = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        OresFuture<Void> childFuture = new OresFuture<>(() -> {
            childRevocationEntered.countDown();
            awaitUninterruptibly(releaseChildRevocation);
            childHookFinished.set(true);
        });
        OresFuture<Void> rootFuture = new OresFuture<>(rootRevoked::countDown);
        rootFuture.whenCompleteRuntime((value, error) -> {
            subscriberSawRevokedChild.set(childHookFinished.get());
            rootCallback.countDown();
        });
        injectOwned(child, childFuture);
        injectOwned(root, rootFuture);

        Thread childCanceller = Thread.ofVirtual().start(() -> {
            try { assertTrue(child.cancel()); }
            catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
        });
        Thread rootCanceller = null;
        try {
            assertTrue(childRevocationEntered.await(3, TimeUnit.SECONDS));
            rootCanceller = Thread.ofVirtual().start(() -> {
                try { assertTrue(root.cancel()); }
                catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
            });
            assertTrue(rootRevoked.await(3, TimeUnit.SECONDS),
                    "root producer revocation must progress while child hook waits");
            assertTrue(rootFuture.isCancelled());
            assertTrue(childFuture.isCancelled());
            assertFalse(rootCallback.await(100, TimeUnit.MILLISECONDS),
                    "root subscriber cannot fire before child hook has revoked");
            assertFalse(subscriberSawRevokedChild.get());
        } finally {
            releaseChildRevocation.countDown();
            childCanceller.join();
            if (rootCanceller != null) rootCanceller.join();
        }
        assertNull(failure.get());
        assertTrue(rootCallback.await(3, TimeUnit.SECONDS));
        assertTrue(subscriberSawRevokedChild.get());
        assertTrue(childHookFinished.get());
        assertTrue(root.isCancelRequested());
        assertTrue(child.isCancelRequested());
        root.close();
    }

    @Test
    void producerHookMayReenterAncestorCancellationWithoutDeadlock()
            throws Exception {
        OresTaskScope root = OresTaskScope.compilerOwned();
        OresTaskScope child = root.childScope();
        AtomicInteger rootSubscribers = new AtomicInteger();
        AtomicInteger childSubscribers = new AtomicInteger();
        OresFuture<Void> rootFuture = new OresFuture<>();
        OresFuture<Void> childFuture = new OresFuture<>(() -> root.cancel());
        rootFuture.whenCompleteRuntime((value, error) -> rootSubscribers.incrementAndGet());
        childFuture.whenCompleteRuntime((value, error) -> childSubscribers.incrementAndGet());
        injectOwned(root, rootFuture);
        injectOwned(child, childFuture);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread cancelling = Thread.ofVirtual().start(() -> {
            try { assertTrue(child.cancel()); }
            catch (Throwable thrown) { failure.set(thrown); }
        });
        try {
            cancelling.join(3_000L);
            assertFalse(cancelling.isAlive(), "reentrant root cancel must finish");
            assertNull(failure.get());
            assertEquals(1, rootSubscribers.get());
            assertEquals(1, childSubscribers.get());
            assertTrue(rootFuture.isCancelled());
            assertTrue(childFuture.isCancelled());
            assertFalse(root.cancel());
            assertFalse(child.cancel());
            assertEquals(1, rootSubscribers.get());
            assertEquals(1, childSubscribers.get());
        } finally {
            // Avoid trapping the host test process on a broken implementation.
            if (cancelling.isAlive()) cancelling.interrupt();
        }
        root.close();
    }

    @Test
    void independentRootsDoNotSerializeCancellationHookProgress()
            throws Exception {
        OresTaskScope stalled = OresTaskScope.compilerOwned();
        OresTaskScope independent = OresTaskScope.compilerOwned();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OresFuture<Void> blocked = new OresFuture<>(() -> {
            entered.countDown();
            awaitUninterruptibly(release);
        });
        OresFuture<Void> free = new OresFuture<>();
        injectOwned(stalled, blocked);
        injectOwned(independent, free);
        Thread first = Thread.ofVirtual().start(stalled::cancel);
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(independent.cancel());
            assertTrue(free.isCancelled());
        } finally {
            release.countDown();
            first.join();
        }
        stalled.close();
        independent.close();
    }

    @Test
    void lateWaitersAcrossDeferredCancelCasDeliverOnlyOnce() throws Exception {
        final int rounds = 192;
        for (int iteration = 0; iteration < rounds; iteration++) {
            AtomicInteger hooks = new AtomicInteger();
            AtomicInteger observed = new AtomicInteger();
            OresFuture<Void> future = new OresFuture<>(hooks::incrementAndGet);
            CountDownLatch subscribe = new CountDownLatch(1);
            CountDownLatch subscribed = new CountDownLatch(1);
            AtomicReference<Throwable> problem = new AtomicReference<>();
            Thread lateSubscriber = Thread.ofVirtual().start(() -> {
                try {
                    awaitUninterruptibly(subscribe);
                    future.whenCompleteRuntime((ignored, failure) -> {
                        if (!(failure instanceof java.util.concurrent.CancellationException))
                            problem.compareAndSet(null, new AssertionError(
                                    "late subscriber lost cancellation identity"));
                        observed.incrementAndGet();
                    });
                } catch (Throwable failure) {
                    problem.set(failure);
                } finally {
                    subscribed.countDown();
                }
            });
            Runnable deferred = future.cancelDeferredNotification();
            assertNotNull(deferred);
            assertTrue(future.isCancelled());
            assertEquals(1, hooks.get());
            subscribe.countDown();
            try {
                assertTrue(subscribed.await(3, TimeUnit.SECONDS));
                assertEquals(0, observed.get(),
                        "deferred cancellation cannot notify before scope revocation");
                assertNull(future.runtimeTerminalStateOrNull(),
                        "a deferred cancellation must not be treated as await-ready");
            } finally {
                deferred.run();
                lateSubscriber.join();
            }
            assertNull(problem.get());
            assertEquals(1, observed.get());
            deferred.run();
            assertEquals(1, observed.get());
            assertFalse(future.cancel(true));
            assertEquals(0, future.pendingRuntimeWaiterCount());
        }
    }

}
