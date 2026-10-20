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
}
