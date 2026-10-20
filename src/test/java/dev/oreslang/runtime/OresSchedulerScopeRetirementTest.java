package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A terminal Task future must not outlive deferred physical scope retirement.
 * A cancelled future is a separate revocation signal, not a reclamation proof.
 */
final class OresSchedulerScopeRetirementTest {
    @Test
    void successfulParentFutureWaitsForChildCarrierToExit() throws Exception {
        CountDownLatch childInCarrier = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        CountDownLatch parentCarrierExited = new CountDownLatch(1);
        TaskScope scope = new TaskScope();
        TaskScope.Registration childTicket = scope.reserve();
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> child = scheduler.start(resume -> {
                childTicket.beganExecution();
                childInCarrier.countDown();
                if (!releaseChild.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("child execution never released");
                }
                return OresScheduler.done(42);
            }, childTicket::quiesced);
            childTicket.attach(child);
            try {
                assertTrue(childInCarrier.await(5, TimeUnit.SECONDS));
                OresFuture<Integer> parent = scheduler.start(new OresScheduler.Task<>() {
                    @Override
                    public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                        return OresScheduler.done(7);
                    }
                    @Override
                    public void afterCarrierQuiescent(Runnable retire) {
                        scope.close().whenCompleteRuntime((value, error) -> retire.run());
                        parentCarrierExited.countDown();
                    }
                });
                assertTrue(parentCarrierExited.await(5, TimeUnit.SECONDS));
                assertFalse(parent.isDone(), "success cannot precede scoped descendant retirement");
                assertFalse(scope.drained().isDone(), "child still holds a carrier lease");
                releaseChild.countDown();
                assertEquals(7, parent.get(5, TimeUnit.SECONDS));
                assertNull(scope.drained().get(5, TimeUnit.SECONDS));
            } finally {
                releaseChild.countDown();
            }
        }
    }

    @Test
    void failedParentFutureAlsoWaitsForChildRetirement() throws Exception {
        CountDownLatch childInCarrier = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        CountDownLatch parentCarrierExited = new CountDownLatch(1);
        TaskScope scope = new TaskScope();
        TaskScope.Registration childTicket = scope.reserve();
        IllegalStateException reason = new IllegalStateException("parent failed");
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Void> child = scheduler.start(resume -> {
                childTicket.beganExecution();
                childInCarrier.countDown();
                if (!releaseChild.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("child execution never released");
                }
                return OresScheduler.done(null);
            }, childTicket::quiesced);
            childTicket.attach(child);
            try {
                assertTrue(childInCarrier.await(5, TimeUnit.SECONDS));
                OresFuture<Object> parent = scheduler.start(new OresScheduler.Task<>() {
                    @Override
                    public OresScheduler.Step<Object> resume(OresScheduler.Resume resume) {
                        throw reason;
                    }
                    @Override
                    public void afterCarrierQuiescent(Runnable retire) {
                        scope.close().whenCompleteRuntime((value, error) -> retire.run());
                        parentCarrierExited.countDown();
                    }
                });
                assertTrue(parentCarrierExited.await(5, TimeUnit.SECONDS));
                assertFalse(parent.isDone(), "failure cannot release descendant lifetime early");
                releaseChild.countDown();
                ExecutionException observed = assertThrows(ExecutionException.class,
                        () -> parent.get(5, TimeUnit.SECONDS));
                assertSame(reason, observed.getCause());
                assertNull(scope.drained().get(5, TimeUnit.SECONDS));
            } finally {
                releaseChild.countDown();
            }
        }
    }
    @Test
    void duplicatePhysicalRetirementCallbacksAreIdempotent() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Runnable> retireHook =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger releases =
                new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch hookPublished = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> task = scheduler.start(new OresScheduler.Task<>() {
                @Override
                public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    return OresScheduler.done(17);
                }
                @Override
                public void afterCarrierQuiescent(Runnable retire) {
                    retireHook.set(retire);
                    hookPublished.countDown();
                }
            }, releases::incrementAndGet);
            assertTrue(hookPublished.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone(), "deferred retirement has not yet occurred");
            retireHook.get().run();
            retireHook.get().run();
            assertEquals(17, task.get(5, TimeUnit.SECONDS));
            assertEquals(1, releases.get(), "resources must retire exactly once");
        }
    }

    @Test
    void cancelledFutureStillNeedsPhysicalRetirementCallback() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Runnable> retireHook =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger releases =
                new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch hookPublished = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> task = scheduler.start(new OresScheduler.Task<>() {
                @Override
                public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    return OresScheduler.done(17);
                }
                @Override
                public void afterCarrierQuiescent(Runnable retire) {
                    retireHook.set(retire);
                    hookPublished.countDown();
                }
            }, releases::incrementAndGet);
            assertTrue(hookPublished.await(5, TimeUnit.SECONDS));
            assertTrue(task.cancel(false));
            assertTrue(task.isCancelled());
            assertEquals(0, releases.get(), "cancellation is not physical quiescence");
            retireHook.get().run();
            retireHook.get().run();
            assertEquals(1, releases.get());
        }
    }

}
