package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Behavioral coverage for successful abandonment and same-carrier isolation. */
final class TaskOwnedMutexOwnerIsolationTest {
    @Test
    void successfulTaskStillPoisonsAndReleasesForgottenSharedGuard() throws Exception {
        var shared = OresMutex.shared(new int[] { 0 });
        var held = new AtomicReference<OresMutex.Guard<int[]>>();
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> owner = scheduler.startTracked(resume -> {
                var guard = shared.tryLock().orElseThrow();
                held.set(guard);
                guard.value()[0] = 23;
                return OresScheduler.done(7);
            });
            var outcome = assertInstanceOf(TaskHandle.Success.class,
                    owner.getDone().get(4, TimeUnit.SECONDS));
            assertEquals(7, outcome.value());
            assertTrue(owner.cleanupFailures().get(3, TimeUnit.SECONDS).isEmpty());
            assertTrue(held.get().released());
            assertTrue(shared.isPoisoned(),
                    "successful return does not commit a forgotten mutable guard");
            assertThrows(OresMutex.PoisonedMutexException.class, shared::tryLock);
            shared.recover(value -> { value[0] = 0; return null; });
            var next = shared.tryLock().orElseThrow();
            try {
                assertEquals(0, next.value()[0]);
            } finally {
                next.release();
            }
        }
    }

    @Test
    void foreignTaskOnSameCarrierCannotReadReleaseOrFailLiveGuard() throws Exception {
        var shared = OresMutex.shared(new int[] { 0 });
        var held = new AtomicReference<OresMutex.Guard<int[]>>();
        var acquired = new CountDownLatch(1);
        var suspended = new OresFuture<Integer>();
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> owner = scheduler.startTracked(resume -> {
                held.set(shared.tryLock().orElseThrow());
                acquired.countDown();
                return OresScheduler.await(suspended);
            });
            try {
                assertTrue(acquired.await(3, TimeUnit.SECONDS));
                TaskHandle<Integer> foreign = scheduler.startTracked(resume -> {
                    var guard = held.get();
                    assertThrows(IllegalStateException.class, guard::value);
                    assertThrows(IllegalStateException.class, guard::release);
                    assertThrows(IllegalStateException.class, guard::fail);
                    assertFalse(guard.released());
                    assertFalse(shared.isPoisoned(),
                            "rejected foreign fail must not poison the owner's guard");
                    assertTrue(shared.tryLock().isEmpty());
                    return OresScheduler.done(1);
                });
                assertInstanceOf(TaskHandle.Success.class,
                        foreign.getDone().get(4, TimeUnit.SECONDS));
                assertTrue(foreign.cleanupFailures().get(3, TimeUnit.SECONDS).isEmpty());
                assertFalse(owner.getDone().isDone());
                assertFalse(held.get().released());
            } finally {
                owner.cancel("finish owner isolation test");
            }
            assertInstanceOf(TaskHandle.Cancelled.class,
                    owner.getDone().get(4, TimeUnit.SECONDS));
            assertTrue(owner.cleanupFailures().get(3, TimeUnit.SECONDS).isEmpty());
            assertTrue(held.get().released());
            assertTrue(shared.isPoisoned());
        }
    }
}
