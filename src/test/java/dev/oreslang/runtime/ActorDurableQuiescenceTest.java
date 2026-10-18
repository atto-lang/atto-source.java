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
    void queuedMessagesCannotExecuteWhileFreezeProofIsHeld() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(3);
        AtomicInteger callbacks = new AtomicInteger();
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 32))) {
            var actor = runtime.<Integer>spawnShared(() -> (message, ctx) -> {
                callbacks.incrementAndGet();
                if (message == 1) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test callback was not released");
                    }
                }
                completed.countDown();
            });
            try {
                actor.send(1);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                actor.send(2);
                var pending = runtime.requestDurableQuiescence(actor);
                assertFalse(pending.isDone(),
                        "freeze must not complete before actor turn and carrier exit");
                actor.send(3);
                release.countDown();
                var proof = pending.get(5, TimeUnit.SECONDS);
                assertTrue(proof.valid());
                assertEquals(actor.id(), proof.actorId());
                assertEquals(1, callbacks.get(),
                        "freeze forbids a newly admitted callback while held");
                assertFalse(completed.await(100, TimeUnit.MILLISECONDS),
                        "queued messages stay queued during quiescence");
                assertThrows(IllegalStateException.class,
                        () -> runtime.requestDurableQuiescence(actor).join());
                proof.close();
                assertTrue(completed.await(5, TimeUnit.SECONDS));
                assertEquals(3, callbacks.get());
                assertFalse(proof.valid());
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
            var proof = runtime.requestDurableQuiescence(actor)
                    .get(3, TimeUnit.SECONDS);
            assertTrue(proof.valid());
            actor.send("queued");
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
    void hostCallbacksRunOffCarrierAfterFreezeCompletion() throws Exception {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 32))) {
            var actor = runtime.<String>spawnShared(() -> (message, ctx) -> { });
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
