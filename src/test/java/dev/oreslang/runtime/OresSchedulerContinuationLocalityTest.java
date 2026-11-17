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
    void aResumedContinuationReturnsToItsIdleCarrier() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(4, 64)) {
            for (int round = 0; round < 50; round++) {
                OresFuture<Integer> pending = new OresFuture<>();
                AtomicReference<Thread> before = new AtomicReference<>();
                AtomicReference<Thread> after = new AtomicReference<>();
                CountDownLatch suspended = new CountDownLatch(1);
                AtomicInteger turns = new AtomicInteger();
                OresFuture<Integer> task = scheduler.start(resume -> {
                    if (turns.getAndIncrement() == 0) {
                        before.set(Thread.currentThread());
                        suspended.countDown();
                        return OresScheduler.await(pending);
                    }
                    after.set(Thread.currentThread());
                    return OresScheduler.done((Integer) resume.value() + 1);
                });
                assertTrue(suspended.await(5, TimeUnit.SECONDS));
                Thread producer = Thread.ofPlatform().start(() -> pending.completeFromRuntime(41));
                producer.join();
                assertEquals(42, task.get(5, TimeUnit.SECONDS));
                assertSame(before.get(), after.get(), "round " + round + " migrated off an idle carrier");
                assertNotSame(producer, after.get());
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
