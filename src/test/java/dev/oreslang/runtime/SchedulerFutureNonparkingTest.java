package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SchedulerFutureNonparkingTest {
    @Test
    void pendingHostWaitsAreRejectedOnSchedulerTurns() throws Exception {
        var pending = new OresFuture<Integer>();
        try (var scheduler = new OresScheduler(3)) {
            List<OresFuture<Void>> checks = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                checks.add(scheduler.start(resume -> {
                    assertSame(scheduler, OresScheduler.current());
                    assertTrue(OresScheduler.isSchedulerCarrierThread());
                    assertThrows(IllegalStateException.class,
                        () -> pending.get(50, TimeUnit.MILLISECONDS));
                    assertThrows(IllegalStateException.class, pending::get);
                    assertThrows(IllegalStateException.class, pending::join);
                    assertThrows(TimeoutException.class,
                        () -> pending.get(0, TimeUnit.NANOSECONDS));
                    assertEquals(42, OresFuture.completed(42).get().intValue());
                    assertEquals(42, OresFuture.completed(42).join().intValue());
                    return OresScheduler.done(null);
                }));
            }
            for (var check : checks) check.get(5, TimeUnit.SECONDS);
            assertEquals(101, scheduler.start(resume -> OresScheduler.done(101))
                    .get(5, TimeUnit.SECONDS).intValue());
        } finally {
            pending.cancel(false);
        }
    }

    @Test
    void ordinaryHostWaitAndLogicalAwaitRemainAvailable() throws Exception {
        var pending = new OresFuture<Integer>();
        assertThrows(TimeoutException.class, () -> pending.get(1, TimeUnit.MILLISECONDS));
        try (var scheduler = new OresScheduler(1)) {
            var stage = new java.util.concurrent.atomic.AtomicInteger();
            OresFuture<Integer> task = scheduler.start(resume -> {
                if (stage.getAndIncrement() == 0) return OresScheduler.await(pending);
                assertNull(resume.failure());
                return OresScheduler.done((Integer) resume.value());
            });
            pending.completeFromRuntime(42);
            assertEquals(42, pending.get(5, TimeUnit.SECONDS).intValue());
            assertEquals(42, task.get(5, TimeUnit.SECONDS).intValue());
        }
    }
}
