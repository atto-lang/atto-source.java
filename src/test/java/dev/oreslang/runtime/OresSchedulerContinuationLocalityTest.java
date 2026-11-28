package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** An awaited task resumes on the carrier that ran it, unless that carrier is busy. */
final class OresSchedulerContinuationLocalityTest {

    @Test
    void resumedContinuationPrefersHomeButCanBeStolenAfterGrace() throws Exception {
        // Physical affinity is a bounded hint, not a guarantee under OS
        // preemption. Inspect actual placement/dequeue accounting instead of
        // assuming a 50us grace always outlasts host scheduling latency.
        try (LocalityCarrierPool pool = new LocalityCarrierPool(4, 64,
                r -> Thread.ofPlatform().daemon(true).unstarted(r),
                TimeUnit.MICROSECONDS.toNanos(50), 2)) {
            pool.prestartAll();
            try (OresScheduler scheduler = OresScheduler.runtimeOwned("locality-test", 4, pool)) {
                for (int round = 0; round < 50; round++) {
                    OresFuture<Integer> pending = new OresFuture<>();
                    AtomicReference<Thread> before = new AtomicReference<>();
                    AtomicReference<Thread> after = new AtomicReference<>();
                    AtomicReference<ExecutionContext> context = new AtomicReference<>();
                    CountDownLatch suspended = new CountDownLatch(1);
                    AtomicInteger turns = new AtomicInteger();
                    var baseline = pool.stats();
                    OresFuture<Integer> task = scheduler.start(resume -> {
                        if (turns.getAndIncrement() == 0) {
                            before.set(Thread.currentThread());
                            context.set(OresScheduler.currentExecutionContext());
                            suspended.countDown();
                            return OresScheduler.await(pending);
                        }
                        assertSame(scheduler, OresScheduler.current());
                        assertSame(context.get(), OresScheduler.currentExecutionContext());
                        after.set(Thread.currentThread());
                        return OresScheduler.done((Integer) resume.value() + 1);
                    });
                    assertTrue(suspended.await(5, TimeUnit.SECONDS));
                    Thread producer = Thread.ofPlatform().start(() -> pending.completeFromRuntime(41));
                    producer.join();
                    assertEquals(42, task.get(5, TimeUnit.SECONDS));
                    assertEquals(2, turns.get());
                    assertNotSame(producer, after.get());
                    var observed = pool.stats();
                    assertEquals(1L, observed.preferredPlacements() - baseline.preferredPlacements(),
                            "await must submit one preferred continuation");
                    long local = observed.localRuns() - baseline.localRuns();
                    long stolen = observed.steals() - baseline.steals();
                    assertEquals(1L, local + stolen, "one preferred continuation must execute");
                    if (local == 1) assertSame(before.get(), after.get());
                    else assertNotSame(before.get(), after.get());
                }
            }
        }
    }

    @Test
    void aContinuationWhoseCarrierIsBusyMigrates() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2, 64)) {
            OresFuture<Integer> pending = new OresFuture<>();
            AtomicReference<Thread> before = new AtomicReference<>();
            AtomicReference<Thread> after = new AtomicReference<>();
            CountDownLatch suspended = new CountDownLatch(1);
            CountDownLatch hogging = new CountDownLatch(1);
            AtomicReference<Thread> hog = new AtomicReference<>();
            CountDownLatch releaseHog = new CountDownLatch(1);
            AtomicInteger turns = new AtomicInteger();
            OresFuture<Integer> task = scheduler.start(resume -> {
                if (turns.getAndIncrement() == 0) {
                    before.set(Thread.currentThread());
                    // A child started here prefers this carrier and occupies it
                    // only after this turn suspends, so the await below finds
                    // its home carrier busy.
                    scheduler.start(child -> {
                        hog.set(Thread.currentThread());
                        hogging.countDown();
                        releaseHog.await();
                        return OresScheduler.done(0);
                    });
                    suspended.countDown();
                    return OresScheduler.await(pending);
                }
                after.set(Thread.currentThread());
                return OresScheduler.done(7);
            });
            try {
                assertTrue(suspended.await(5, TimeUnit.SECONDS));
                assertTrue(hogging.await(5, TimeUnit.SECONDS));
                pending.completeFromRuntime(1);
                assertEquals(7, task.get(5, TimeUnit.SECONDS),
                        "a busy home carrier must not hold the continuation");
                assertNotSame(hog.get(), after.get(), "ran on the carrier that is still held");
            } finally {
                releaseHog.countDown();
            }
        }
    }
}
