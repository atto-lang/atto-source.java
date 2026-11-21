package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Proves that discarded queued turns retire their dispatch reservations. */
@Timeout(15)
final class SchedulerAbandonedDispatchTest {
    @Test
    void droppedRuntimeOwnedTurnRetiresWithoutRunningGuest() {
        LinkedBlockingQueue<Runnable> mailbox = new LinkedBlockingQueue<>();
        AtomicInteger guestTurns = new AtomicInteger();
        AtomicInteger physicalRetirements = new AtomicInteger();
        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "discarded-actor-turn", 1, mailbox::add);
        try {
            OresFuture<Void> future = scheduler.startWithPhysicalRetirement(resume -> {
                guestTurns.incrementAndGet();
                return OresScheduler.done(null);
            }, physicalRetirements::incrementAndGet);
            Runnable queued = mailbox.remove();
            assertInstanceOf(OresScheduler.AbandonableTurn.class, queued);
            assertTrue(future.cancel(false));
            assertEquals(0, physicalRetirements.get(),
                    "queued dispatch still owns a physical admission reservation");
            ((OresScheduler.AbandonableTurn) queued).abandoned();
            assertEquals(1, physicalRetirements.get());
            queued.run(); // must be permanently inert after abandonment
            ((OresScheduler.AbandonableTurn) queued).abandoned();
            assertEquals(1, physicalRetirements.get());
            assertEquals(0, guestTurns.get());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void shutdownNowRetiresQueuedOwnedExecutorTurn() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch queuedRetired = new CountDownLatch(1);
        AtomicInteger queuedRuns = new AtomicInteger();
        AtomicBoolean closeFailed = new AtomicBoolean();

        OresScheduler scheduler = new OresScheduler(1);
        try {
            scheduler.start(resume -> {
                firstEntered.countDown();
                boolean interrupted = false;
                for (;;) {
                    try {
                        releaseFirst.await();
                        break;
                    } catch (InterruptedException ex) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return OresScheduler.done(null);
            });
            assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
            OresFuture<Void> pending = scheduler.startWithPhysicalRetirement(
                    resume -> {
                        queuedRuns.incrementAndGet();
                        return OresScheduler.done(null);
                    }, queuedRetired::countDown);

            Thread shutdown = new Thread(() -> {
                try {
                    scheduler.close();
                } catch (RuntimeException failure) {
                    closeFailed.set(true);
                }
            }, "queued-dispatch-close");
            shutdown.start();
            try {
                assertTrue(queuedRetired.await(3, TimeUnit.SECONDS));
                assertTrue(pending.isCancelled());
                assertEquals(0, queuedRuns.get());
            } finally {
                releaseFirst.countDown();
            }
            shutdown.join(5000);
            assertFalse(shutdown.isAlive());
            assertFalse(closeFailed.get(), "scheduler close must not hang on dropped turns");
        } finally {
            releaseFirst.countDown();
            scheduler.close();
        }
    }

    @Test
    void actorStopDrainsQueuedSourceTaskBeforePublishingDone() throws Exception {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket ticket = gate.newTicket();
        assertTrue(ticket.enterAsync().isDone());
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger retired = new AtomicInteger();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                context.runtime().startActorTaskWithPhysicalRetirement(
                        resume -> {
                            executed.incrementAndGet();
                            return OresScheduler.done(null);
                        },
                        () -> {
                            retired.incrementAndGet();
                            ticket.close();
                        });
                context.self().stop();
            });
            actor.send("close");
            actor.done().get(4, TimeUnit.SECONDS);
            assertEquals(0, executed.get(),
                    "stopped actor must not dispatch abandoned guest continuation");
            assertEquals(1, retired.get(),
                    "abandoned source task must retire its critical cleanup once");
            assertFalse(gate.held());
        } finally {
            ticket.close();
        }
    }
}
