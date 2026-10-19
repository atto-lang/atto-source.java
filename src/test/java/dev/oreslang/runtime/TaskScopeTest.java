package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task scope retirement is stronger than a cancelled/settled Future: the
 * executing carrier must have left the guest turn before parent resumption.
 */
final class TaskScopeTest {
    @Test
    void emptyScopeIsAlreadyQuiescentAndCannotReopen() {
        TaskScope scope = new TaskScope();
        OresFuture<Void> joined = scope.close();
        assertTrue(joined.isDone());
        assertDoesNotThrow(joined::join);
        assertThrows(IllegalStateException.class, scope::reserve);
    }

    @Test
    void pendingChildMaySpawnGrandchildAfterParentBeginsJoining() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration parent = scope.reserve();
        OresFuture<Void> joined = scope.close();
        TaskScope.Registration grandchild = scope.reserve();

        parent.attach(OresFuture.completed(null));
        parent.quiesced();
        assertFalse(joined.isDone());

        grandchild.attach(OresFuture.completed(null));
        grandchild.quiesced();
        assertTrue(joined.isDone());
        assertDoesNotThrow(joined::join);
        assertThrows(IllegalStateException.class, scope::reserve);
    }

    @Test
    void cancelledFutureDoesNotUnlockLiveCarrier() throws Exception {
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskScope scope = new TaskScope();
        TaskScope.Registration ticket = scope.reserve();
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> running = scheduler.start(resume -> {
                executing.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release was not signalled");
                }
                return OresScheduler.done(42);
            }, ticket::quiesced);
            ticket.attach(running);
            try {
                assertTrue(executing.await(5, TimeUnit.SECONDS));
                OresFuture<Void> joined = scope.close();
                assertTrue(running.cancel(false));
                assertTrue(running.isDone(), "cancelled Future is already terminal");
                assertFalse(joined.isDone(), "live carrier still owns child memory");
            } finally {
                release.countDown();
            }
            // The cancellation may have won the Future race, but retirement
            // must not be published until the carrier actually returns.
            assertThrows(RuntimeException.class,
                    () -> scope.close().get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void childFailureWaitsForOtherChildQuiescenceBeforePropagating() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration failed = scope.reserve();
        TaskScope.Registration stillRunning = scope.reserve();
        OresFuture<Void> joined = scope.close();
        failed.attach(OresFuture.failed(new IllegalStateException("child failed")));
        failed.quiesced();
        assertFalse(joined.isDone());
        stillRunning.attach(OresFuture.completed(null));
        stillRunning.quiesced();
        assertTrue(joined.isDone());
        assertThrows(RuntimeException.class, joined::join);
    }
}
