package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real ActorRuntime scheduling tests, not a host-only imitation.
 * The freeze must linearize with mailbox admission and settle only after the
 * physical TurnExecutor has returned, leaving queued mail intact.
 */
@Timeout(20)
final class ActorDurableQuiescenceTest {
    @Test
    void acceptedPreFreezeMessagesMustDrainBeforeHibernateCanSucceed() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(3);
        AtomicInteger callbacks = new AtomicInteger();
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 32))) {
            var actor = runtime.<Integer>spawnShared(() -> (message, ctx) -> {
                callbacks.incrementAndGet();
                if (message == 1) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("test callback was not released");
                }
                delivered.countDown();
            });
            try {
                actor.send(1);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                actor.send(2); // accepted in memory before freeze admission
                var pending = runtime.requestDurableQuiescence(actor);
                assertFalse(pending.isDone(), "running turn must leave before grant");
                assertThrows(ActorRuntime.ActorSealedException.class, () -> actor.send(3),
                        "a message sent during freeze must not be acknowledged");
                release.countDown();
                var rejected = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> pending.get(5, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, rejected.getCause());
                // Failed handoff automatically resumes dispatch for old mail.
                // No previously acknowledged message was lost.
                var proof = runtime.requestDurableQuiescence(actor)
                        .get(5, TimeUnit.SECONDS);
                assertTrue(proof.valid());
                assertEquals(2, callbacks.get());
                assertThrows(ActorRuntime.ActorSealedException.class, () -> actor.send(4));
                proof.close();
                actor.send(5);
                assertTrue(delivered.await(5, TimeUnit.SECONDS));
                assertEquals(3, callbacks.get());
            } finally {
                release.countDown();
                actor.cancel();
            }
        }
    }

    @Test
    void quiescenceProofInvalidatesOnActorTerminationWithoutReactivation() throws Exception {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 32))) {
            var actor = runtime.<String>spawnShared(() -> (message, ctx) -> { });
            var uninitialized = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> runtime.requestDurableQuiescence(actor).get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, uninitialized.getCause());
            actor.ready().get(3, TimeUnit.SECONDS);
            var proof = runtime.requestDurableQuiescence(actor)
                    .get(3, TimeUnit.SECONDS);
            assertTrue(proof.valid());
            assertThrows(ActorRuntime.ActorSealedException.class,
                    () -> actor.send("queued"));
            proof.stopAfterHibernate();
            assertFalse(proof.valid());
            assertThrows(ActorRuntime.ActorTerminatedException.class,
                    () -> actor.send("must-not-execute"));
        }
    }

    @Test
    void suspendedReceiveIsNotMistakenForSafeSnapshotBoundary() throws Exception {
        CountDownLatch suspended = new CountDownLatch(1);
        OresFuture<Void> blocker = new OresFuture<>();
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 32))) {
            var actor = runtime.<String>spawnShared(() -> (message, ctx) -> {
                if (message.equals("suspend")) {
                    runtime.suspendCurrentUserMailboxUntil(blocker);
                    suspended.countDown();
                }
            });
            try {
                actor.send("suspend");
                assertTrue(suspended.await(3, TimeUnit.SECONDS));
                var attempt = runtime.requestDurableQuiescence(actor);
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> attempt.get(2, TimeUnit.SECONDS),
                        "a suspended source receive still owns logical actor state");
            } finally {
                blocker.completeFromRuntime(null);
                actor.cancel();
            }
        }
    }

    @Test
    void proofWaitsForHostTurnExecutorToLeave() throws Exception {
        CountDownLatch callbackExited = new CountDownLatch(1);
        CountDownLatch releaseHostBoundary = new CountDownLatch(1);
        AtomicInteger guestTurns = new AtomicInteger();
        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            callbackExited.countDown();
            try {
                if (!releaseHostBoundary.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("host test boundary timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("host test interrupted", e);
            }
        };
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 32), executor)) {
            var actor = runtime.<Integer>spawnShared(() -> (msg, ctx) ->
                    guestTurns.incrementAndGet());
            try {
                actor.send(1);
                assertTrue(callbackExited.await(3, TimeUnit.SECONDS));
                var pending = runtime.requestDurableQuiescence(actor);
                assertFalse(pending.isDone(),
                        "guest return is not the physical TurnExecutor leave boundary");
                releaseHostBoundary.countDown();
                var proof = pending.get(5, TimeUnit.SECONDS);
                assertTrue(proof.valid());
                assertEquals(1, guestTurns.get());
                proof.close();
            } finally {
                releaseHostBoundary.countDown();
                actor.cancel();
            }
        }
    }

    @Test
    void hostCallbacksRunOffCarrierAfterFreezeCompletion() throws Exception {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 32))) {
            var actor = runtime.<String>spawnShared(() -> (message, ctx) -> { });
            actor.ready().get(3, TimeUnit.SECONDS);
            var check = runtime.requestDurableQuiescence(actor)
                    .thenApply(proof -> {
                        assertFalse(ActorRuntime.isActorCarrierThread());
                        assertFalse(ActorRuntime.inActorExecution());
                        proof.close();
                        return true;
                    });
            assertTrue(check.get(3, TimeUnit.SECONDS));
            actor.cancel();
        }
    }
}
