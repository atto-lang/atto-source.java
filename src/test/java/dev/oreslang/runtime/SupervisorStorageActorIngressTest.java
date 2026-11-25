package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-driven proof using ACTUAL ActorRuntime.ActorCell dispatch, mailbox and
 * continuation queues, not a manually invoked supervisor gate callback.
 * Source-level define-supervisor lowering is a separate compiler milestone.
 */
@Timeout(25)
final class SupervisorStorageActorIngressTest {
    private static final class Store
            implements SupervisorStorageEpochAuthority.DurableGenerationStore {
        private long generation;
        @Override public synchronized long currentGeneration() { return generation; }
        @Override public synchronized long reserveAndSyncNext(long expected, String token) {
            if (expected != generation || token == null
                    || !token.matches("[0-9a-f]{32}"))
                throw new SecurityException("invalid supervisor generation admission");
            return ++generation;
        }
    }

    @Test void actualActorCellCannotDequeueOldMailAfterTakeover() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var owner = runtime.createSupervisor();
            var gate = owner.registerStoragePartition("kafka-m6/mail", new Store());
            var first = gate.activate();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var acceptedNew = new CountDownLatch(1);
            var oldUnexpected = new AtomicInteger();
            var child = owner.<String>spawnStorageBoundActorHost(gate, first,
                    IsolatePolicy.developer(), context -> (message, actor) -> {
                        if ("hold".equals(message)) {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("test hold timed out");
                        } else {
                            oldUnexpected.incrementAndGet();
                        }
                    });
            try {
                child.ready().get(5, TimeUnit.SECONDS);
                child.send("hold");
                assertTrue(entered.await(5, TimeUnit.SECONDS),
                        "actual ActorCell mailbox turn must begin");
                child.send("late"); // buffered in the REAL actor mailbox
                assertThrows(SupervisorStorageEpochAuthority.QuiescencePendingException.class,
                        gate::activate, "new durable epoch cannot overlap executing old ActorCell");
                assertFalse(gate.physicallyQuiescent());
                release.countDown();
                child.cancel(); // logical revoke alone is NOT physical retirement
                assertTrue(child.awaitTermination(5, TimeUnit.SECONDS),
                        "old ActorCell must finalize before successor epoch");
                assertTrue(gate.physicallyQuiescent(), "old ActorCell lifetime must retire");
                var second = gate.activate();
                assertEquals(2L, second.generation());
                assertEquals(0, oldUnexpected.get(),
                        "revoked queued ordinary actor mail must not mutate state");
                var replacement = owner.<String>spawnStorageBoundActorHost(gate, second,
                        IsolatePolicy.developer(), context -> (message, actor) -> {
                            if ("new".equals(message)) acceptedNew.countDown();
                        });
                try {
                    replacement.ready().get(5, TimeUnit.SECONDS);
                    replacement.send("new");
                    assertTrue(acceptedNew.await(5, TimeUnit.SECONDS));
                } finally { replacement.cancel(); }
                assertEquals(0, oldUnexpected.get());
            } finally {
                release.countDown();
                child.cancel();
            }
        }
    }

    @Test void onePhysicalPartitionOwnerEvenWhenFirstActorIdleAndRevoked() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var owner = runtime.createSupervisor();
            var foreignSupervisor = runtime.createSupervisor();
            var gate = owner.registerStoragePartition("kafka-m6/owner", new Store());
            var first = gate.activate();
            // A failed spawn must roll its exclusive lease back.
            assertThrows(NullPointerException.class, () ->
                    owner.<String>spawnStorageBoundActorHost(gate, first,
                            null, context -> (mail, actor) -> { }));
            var actor = owner.<String>spawnStorageBoundActorHost(gate, first,
                    IsolatePolicy.developer(), context -> (mail, self) -> { });
            try {
                // The exclusive binding must be claimed BEFORE the actor's
                // first scheduled turn, not after ready() or mailbox dispatch.
                assertThrows(SecurityException.class, () ->
                        owner.<String>spawnStorageBoundActorHost(gate, first,
                                IsolatePolicy.developer(), context -> (mail, self) -> { }),
                        "duplicate actor owner must be rejected before first ready turn");
                actor.ready().get(5, TimeUnit.SECONDS);
                assertThrows(SecurityException.class, () ->
                        owner.<String>spawnStorageBoundActorHost(gate, first,
                                IsolatePolicy.developer(), context -> (mail, self) -> { }),
                        "second owner using same durable epoch would violate exclusivity");
                assertThrows(SecurityException.class, () ->
                        foreignSupervisor.<String>spawnStorageBoundActorHost(gate, first,
                                IsolatePolicy.developer(), context -> (mail, self) -> { }));
                assertFalse(gate.physicallyQuiescent(), "idle ActorCell still owns partition");
                assertThrows(SupervisorStorageEpochAuthority.QuiescencePendingException.class,
                        gate::activate, "cannot replace an idle, not yet retired actor");
                assertThrows(SecurityException.class, () -> actor.send("late"),
                        "revoked actor must reject sender before mailbox allocation");
                assertThrows(SecurityException.class, () -> actor.sendAsync("late"),
                        "async producer must not bypass revoked actor admission");
            } finally {
                actor.cancel();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                        "actor must physically finalize and return lifetime claim");
            }
            assertTrue(gate.physicallyQuiescent());
            var second = gate.activate();
            assertEquals(2, second.generation());
            var replacement = owner.<String>spawnStorageBoundActorHost(gate, second,
                    IsolatePolicy.developer(), context -> (mail, self) -> { });
            replacement.ready().get(5, TimeUnit.SECONDS);
            replacement.cancel();
            assertTrue(replacement.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void failedPhysicalCleanupQuarantinesPartitionAgainstGenerationReuse() throws Exception {
        try (var runtime = new ActorRuntime()) {
            // Deliberately fail a trusted runtime finalization hook after
            // actor STOP. A logical done signal is not physical cleanup proof.
            runtime.setActorExitHook(ignored -> {
                throw new IllegalStateException("injected actor execution-domain cleanup failure");
            });
            var owner = runtime.createSupervisor();
            var gate = owner.registerStoragePartition("kafka-m6/cleanup-quarantine", new Store());
            var first = gate.activate();
            var actor = owner.<String>spawnStorageBoundActorHost(gate, first,
                    IsolatePolicy.developer(), context -> (mail, self) -> { });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.cancel();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "logical finalization is allowed to report a failure");
            assertFalse(gate.physicallyQuiescent(),
                    "failed physical cleanup must retain exclusive partition claim");
            assertThrows(SupervisorStorageEpochAuthority.QuiescencePendingException.class,
                    gate::activate, "replacement on an incompletely retired partition is unsafe");
        }
    }

    @Test void revokedActivationCannotCreateOrRunAnotherActorFactory() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var owner = runtime.createSupervisor();
            var gate = owner.registerStoragePartition("kafka-m6/stale-bootstrap", new Store());
            var stale = gate.activate();
            var current = gate.activate();
            var factoriesStarted = new AtomicInteger();
            assertThrows(SecurityException.class, () ->
                    owner.<String>spawnStorageBoundActorHost(gate, stale,
                            IsolatePolicy.developer(), context -> {
                                factoriesStarted.incrementAndGet();
                                return (mail, self) -> { };
                            }), "retired generation must fail before ActorCell registration");
            assertEquals(0, factoriesStarted.get(),
                    "rejected activation must not execute a guest/host behavior factory");
            assertTrue(gate.physicallyQuiescent(),
                    "failed stale bootstrap must not leak a lifetime claim");
            var actor = owner.<String>spawnStorageBoundActorHost(gate, current,
                    IsolatePolicy.developer(), context -> (mail, self) -> { });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.cancel();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(gate.physicallyQuiescent());
        }
    }

    @Test void actualActorContinuationQueuedDuringOldReceiveCannotResumeAfterRevocation() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var owner = runtime.createSupervisor();
            var gate = owner.registerStoragePartition("kafka-m6/continuation", new Store());
            var first = gate.activate();
            var started = new CountDownLatch(1);
            var unblock = new CountDownLatch(1);
            var future = new OresFuture<Integer>();
            var lateFuture = new OresFuture<Integer>();
            var callbacks = new AtomicInteger();
            var lateCallbacks = new AtomicInteger();
            var target = new AtomicReference<ActorRuntime.ContinuationTarget>();
            var actor = owner.<String>spawnStorageBoundActorHost(gate, first,
                    IsolatePolicy.developer(), context -> (message, receiver) -> {
                        target.set(runtime.captureCurrentContinuationTarget());
                        runtime.enqueueOnCompletion(future, target.get(), (value, failure) ->
                                callbacks.incrementAndGet());
                        runtime.enqueueOnCompletion(lateFuture, target.get(), (value, failure) ->
                                lateCallbacks.incrementAndGet());
                        started.countDown();
                        if (!unblock.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("blocked receive");
                    });
            try {
                actor.ready().get(5, TimeUnit.SECONDS);
                actor.send("install");
                assertTrue(started.await(5, TimeUnit.SECONDS));
                future.completeFromRuntime(7); // queued in ACTUAL continuation transport
                assertThrows(SupervisorStorageEpochAuthority.QuiescencePendingException.class,
                        gate::activate);
                // Future producer completing AFTER logical revocation must
                // not reserve CONTROL mailbox capacity for a dead actor.
                lateFuture.completeFromRuntime(9);
                assertEquals(0, lateCallbacks.get());
                unblock.countDown();
                actor.cancel();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                        "old ActorCell must finalize before successor epoch");
                assertTrue(gate.physicallyQuiescent(), "old actor lifetime must finish");
                assertEquals(2L, gate.activate().generation());
                assertEquals(0, callbacks.get(), "stale continuation must be discarded");
                assertEquals(0, lateCallbacks.get(), "post-revocation Future never runs");
            } finally {
                unblock.countDown();
                actor.cancel();
            }
        }
    }
}
