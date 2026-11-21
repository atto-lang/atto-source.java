package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OresSchedulerDeferredRetirementTest {
    @Test
    void schedulerShutdownCannotRetireAnUnreleasedDescendantLease() throws Exception {
        CountDownLatch carrierExited = new CountDownLatch(1);
        AtomicReference<Runnable> releaseDescendants = new AtomicReference<>();
        AtomicInteger retired = new AtomicInteger();
        OresScheduler scheduler = new OresScheduler(2);
        try {
            OresFuture<Integer> future = scheduler.start(new OresScheduler.Task<Integer>() {
                @Override
                public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    return OresScheduler.done(7);
                }

                @Override
                public void afterCarrierQuiescent(Runnable finish) {
                    releaseDescendants.set(finish);
                    carrierExited.countDown();
                }
            }, retired::incrementAndGet);

            assertTrue(carrierExited.await(5, TimeUnit.SECONDS));
            assertEquals(7, future.get(5, TimeUnit.SECONDS));
            scheduler.close();
            assertEquals(0, retired.get(),
                    "scheduler shutdown must not fake descendant physical retirement");
            Runnable finish = releaseDescendants.get();
            assertNotNull(finish);
            finish.run();
            finish.run();
            assertEquals(1, retired.get(),
                    "the post-carrier lifetime callback must retire only once");
        } finally {
            Runnable pending = releaseDescendants.get();
            if (pending != null) pending.run();
            scheduler.close();
        }
    }

    @Test
    void deferredRetirementIsIdempotent() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Runnable> callback = new AtomicReference<>();
        AtomicInteger retired = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> result = scheduler.start(new OresScheduler.Task<Integer>() {
                public OresScheduler.Step<Integer> resume(OresScheduler.Resume value) {
                    return OresScheduler.done(42);
                }
                public void afterCarrierQuiescent(Runnable done) {
                    callback.set(done);
                    entered.countDown();
                }
            }, retired::incrementAndGet);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertEquals(0, retired.get());
            Runnable done = callback.get();
            assertNotNull(done);
            done.run();
            done.run();
            assertEquals(1, retired.get());
        }
    }
}
