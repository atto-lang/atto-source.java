package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class ActorTurnWatchdogTest {
    @Test
    void watchdogObservesBusyActorWithoutInterruptingOrReenteringItsCarrier()
            throws Exception {
        AtomicBoolean release = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch flagged = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                entered.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (!release.get() && System.nanoTime() < deadline) {
                    context.runtime().schedulerSafepoint();
                    if (context.runtime().watchdogPreemptionRequested()) {
                        flagged.countDown();
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        interrupted.set(true);
                    }
                    Thread.onSpinWait();
                }
                context.self().stop();
            });
            try {
                actor.send("busy");
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTrue(flagged.await(3, TimeUnit.SECONDS),
                        "independent watchdog must run while a carrier is occupied");
                ActorRuntime.WatchdogNotice notice = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (notice == null && System.nanoTime() < deadline) {
                    notice = runtime.pollWatchdogNotice().orElse(null);
                    if (notice == null) Thread.onSpinWait();
                }
                assertTrue(notice != null, "trusted host must observe the overrun");
                assertEquals(actor.id(), notice.actorId());
                assertEquals(ActorRuntime.ActorKind.SHARED, notice.kind());
                assertTrue(notice.elapsedNanos() >= ActorTurnWatchdog.OVERRUN_NANOS);
                assertTrue(runtime.watchdogOverruns() >= 1);
                assertFalse(interrupted.get(), "watchdog must never interrupt the carrier");
            } finally {
                release.set(true);
            }
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertFalse(interrupted.get());
        }
    }

    @Test
    void retiringLeaseSuppressesStaleNoticesForReusedCarriers() throws Exception {
        AtomicInteger notices = new AtomicInteger();
        ActorTurnWatchdog.Lease lease =
                ActorTurnWatchdog.enter(ignored -> notices.incrementAndGet());
        lease.close();
        Thread.sleep(20);
        assertEquals(0, notices.get());
        assertFalse(lease.preemptionRequested());
    }

    @Test
    void logicalSchedulerTaskReceivesRequestAndReleasesLease() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            var result = scheduler.start(resume -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (!OresScheduler.preemptionRequested()
                        && System.nanoTime() < deadline) {
                    OresScheduler.checkpointCurrentTurn();
                    Thread.onSpinWait();
                }
                return OresScheduler.done(OresScheduler.preemptionRequested());
            });
            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertTrue(scheduler.watchdogOverruns() >= 1);
            assertFalse(OresScheduler.preemptionRequested(),
                    "host must not retain the carrier's watchdog identity");
        }
    }
}
