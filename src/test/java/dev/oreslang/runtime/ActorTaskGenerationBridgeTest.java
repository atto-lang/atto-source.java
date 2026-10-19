package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Host-only admission proof; no compiled guest source or actor mailbox here. */
final class ActorTaskGenerationBridgeTest {
    private static String digest(char symbol) {
        return String.valueOf(symbol).repeat(64);
    }

    private static ActorUpgradeEpochGate.Image image(char code) {
        return new ActorUpgradeEpochGate.Image(
                digest(code), digest('1'), digest('2'), digest('3'));
    }

    @Test
    void suspendedContinuationPinsOldGenerationAcrossAwaitAndBlocksPublication() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope owner = new OresTaskScope(runtime);
             ActorUpgradeEpochGate gate = new ActorUpgradeEpochGate(18, image('a'))) {
            OresFuture<Integer> unresolved = new OresFuture<>();
            AtomicInteger resumes = new AtomicInteger();
            AtomicReference<Object> logicalTaskIdentity = new AtomicReference<>();
            OresTask<Integer> task = ActorTaskGenerationBridge.start(
                    gate, owner, scheduler, resume -> {
                        Object domain = OresScheduler.currentTaskDomain();
                        assertNotNull(domain);
                        if (resumes.getAndIncrement() == 0) {
                            logicalTaskIdentity.set(domain);
                            return new OresScheduler.Await<Integer>(unresolved);
                        }
                        assertSame(logicalTaskIdentity.get(), domain,
                                "suspends must keep the original Task identity");
                        return new OresScheduler.Done<>(((Integer) resume.value()) + 1);
                    });
            assertEquals(1, gate.livePins(
                    ActorUpgradeEpochGate.LeaseKind.SUSPENDED_TASK));
            ActorUpgradeEpochGate.Candidate candidate = gate.prepare(image('b'));
            assertFalse(gate.publish(candidate),
                    "await releases a carrier, not the old code image");
            assertThrows(IllegalStateException.class,
                    () -> ActorTaskGenerationBridge.start(
                            gate, owner, scheduler,
                            ignored -> new OresScheduler.Done<>(2)));
            unresolved.completeFromRuntime(41);
            assertEquals(42, AsyncRuntime.await(task.getAwaited()));
            assertNull(AsyncRuntime.await(task.getDone()));
            assertEquals(0, gate.livePins());
            assertEquals(2, resumes.get());
            assertTrue(gate.publish(candidate));
            assertEquals(2, gate.epoch());
            assertEquals(image('b'), gate.image());
        }
    }

    @Test
    void cancellingResultDoesNotReleaseCodeWhileGuestTurnIsStillExecuting()
            throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope owner = new OresTaskScope(runtime);
             ActorUpgradeEpochGate gate = new ActorUpgradeEpochGate(19, image('a'))) {
            CountDownLatch insideTurn = new CountDownLatch(1);
            CountDownLatch releaseTurn = new CountDownLatch(1);
            OresTask<Integer> task = ActorTaskGenerationBridge.start(
                    gate, owner, scheduler, ignored -> {
                        insideTurn.countDown();
                        assertTrue(releaseTurn.await(3, TimeUnit.SECONDS));
                        return new OresScheduler.Done<>(5);
                    });
            assertTrue(insideTurn.await(2, TimeUnit.SECONDS));
            ActorUpgradeEpochGate.Candidate candidate = gate.prepare(image('b'));
            try {
                assertTrue(task.cancel());
                assertTrue(task.isCancelled());
                assertEquals(1, gate.livePins());
                assertFalse(gate.publish(candidate));
                assertFalse(task.isQuiescent());
            } finally {
                releaseTurn.countDown();
            }
            assertNull(AsyncRuntime.await(task.getDone()));
            assertEquals(0, gate.livePins());
            assertTrue(gate.publish(candidate));
        }
    }

    @Test
    void cancelledOwnerRejectsWithoutStrandingPreviouslyAcquiredImagePin() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope owner = new OresTaskScope(runtime);
             ActorUpgradeEpochGate gate = new ActorUpgradeEpochGate(20, image('a'))) {
            owner.cancel();
            assertThrows(IllegalStateException.class, () ->
                    ActorTaskGenerationBridge.start(gate, owner, scheduler,
                            ignored -> new OresScheduler.Done<>(3)));
            assertEquals(0, gate.livePins(),
                    "rejected scheduler admission must retire its unused pin");
            var candidate = gate.prepare(image('b'));
            assertTrue(gate.publish(candidate));
        }
    }

    @Test
    void closedSchedulerRejectsWithoutLeakingTheCodeImage() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope owner = new OresTaskScope(runtime);
             ActorUpgradeEpochGate gate = new ActorUpgradeEpochGate(21, image('a'))) {
            OresScheduler scheduler = new OresScheduler(1);
            scheduler.close();
            assertThrows(IllegalStateException.class, () ->
                    ActorTaskGenerationBridge.start(gate, owner, scheduler,
                            ignored -> new OresScheduler.Done<>(3)));
            assertEquals(0, gate.livePins());
            var candidate = gate.prepare(image('b'));
            assertTrue(gate.publish(candidate));
        }
    }

    @Test
    void independentChildTasksAcquireIndependentPins() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope owner = new OresTaskScope(runtime);
             ActorUpgradeEpochGate gate = new ActorUpgradeEpochGate(22, image('a'))) {
            OresFuture<Integer> blocker = new OresFuture<>();
            OresTask<Integer> a = ActorTaskGenerationBridge.start(gate, owner,
                    scheduler, resume -> {
                        if (resume.initial())
                            return new OresScheduler.Await<Integer>(blocker);
                        return new OresScheduler.Done<>(1);
                    });
            OresTask<Integer> b = ActorTaskGenerationBridge.start(gate, owner,
                    scheduler, resume -> {
                        if (resume.initial())
                            return new OresScheduler.Await<Integer>(blocker);
                        return new OresScheduler.Done<>(2);
                    });
            assertEquals(2, gate.livePins(
                    ActorUpgradeEpochGate.LeaseKind.SUSPENDED_TASK));
            var candidate = gate.prepare(image('b'));
            assertFalse(gate.publish(candidate));
            blocker.completeFromRuntime(0);
            assertNull(AsyncRuntime.await(a.getDone()));
            assertNull(AsyncRuntime.await(b.getDone()));
            assertEquals(0, gate.livePins());
            assertTrue(gate.publish(candidate));
        }
    }
}
