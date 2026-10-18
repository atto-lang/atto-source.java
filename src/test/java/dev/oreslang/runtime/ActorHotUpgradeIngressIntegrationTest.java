package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime mailbox/physical-carrier integration, but explicitly
 * NOT actor executable replacement, state serialization or FFI quiescence.
 */
@Timeout(20)
final class ActorHotUpgradeIngressIntegrationTest {
    private static void awaitDrained(ActorRuntime.HotUpgradeIngressFreeze freeze)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!freeze.schedulerDrained() && System.nanoTime() < deadline)
            Thread.sleep(2);
        assertTrue(freeze.schedulerDrained(), "actor scheduler did not quiesce");
    }

    @Test
    void freezeRejectsNewMessagesAndAllowsOldActorToResumeOnAbort() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        delivered.incrementAndGet();
                        if (message == 99) context.self().stop();
                    });
            actor.ready().get(5, TimeUnit.SECONDS);
            try (var freeze = runtime.freezeUserIngressForHotUpgrade(actor)) {
                awaitDrained(freeze);
                assertThrows(ActorRuntime.ActorSealedException.class, () -> actor.send(1));
                assertEquals(0, delivered.get());
            }
            actor.send(99);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, delivered.get());
        }
    }

    @Test
    void admittedTurnMustFinishBeforeSchedulerCanAppearDrained() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch exit = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
/****/        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        if (message == 1) {
                            entered.countDown();
                            try {
                                if (!exit.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("timed out releasing admitted actor turn");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(interrupted);
                            }
                        }
                        delivered.incrementAndGet();
                        if (message == 99) context.self().stop();
                    });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try (var freeze = runtime.freezeUserIngressForHotUpgrade(actor)) {
                assertFalse(freeze.schedulerDrained(),
                        "a live carrier must not be mistaken for a quiescent actor");
                assertThrows(ActorRuntime.ActorSealedException.class, () -> actor.send(2));
                exit.countDown();
                awaitDrained(freeze);
                assertEquals(1, delivered.get(), "rejected user message must not execute");
            } finally {
                exit.countDown();
            }
            actor.send(99);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, delivered.get());
        }
    }

    @Test
    void oldFreezeHandleCannotReleaseAYoungerReservation() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> context.self().stop());
            actor.ready().get(5, TimeUnit.SECONDS);
            var first = runtime.freezeUserIngressForHotUpgrade(actor);
            assertThrows(IllegalStateException.class,
                    () -> runtime.freezeUserIngressForHotUpgrade(actor));
            first.close();
            var second = runtime.freezeUserIngressForHotUpgrade(actor);
            first.close();
            assertThrows(ActorRuntime.ActorSealedException.class,
                    () -> actor.send("not admitted"));
            second.close();
            actor.send("stop");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void pendingMailMayDrainButLateUserAdmissionIsFenced() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger processed = new AtomicInteger();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        if (message == 1) {
                            firstEntered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("source turn did not release");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                        }
                        processed.incrementAndGet();
                        if (message == 99) context.self().stop();
                    });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            actor.send(2); // admitted BEFORE the freeze, must be allowed to drain
            try (var freeze = runtime.freezeUserIngressForHotUpgrade(actor)) {
                assertThrows(ActorRuntime.ActorSealedException.class, () -> actor.send(3));
                release.countDown();
                awaitDrained(freeze);
                assertEquals(2, processed.get(), "pre-freeze messages must not disappear");
            } finally {
                release.countDown();
            }
            actor.send(99);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
