package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Identity bookkeeping only; actor finalization and respawn are host duties. */
final class SupervisorChildRegistryTest {
    @Test
    void logicalIdentitySurvivesReplacementWhileActorIdentityAdvances() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var firstActor = ActorRuntime.ActorId.create();
        var first = registry.register(firstActor);
        assertEquals(1, first.incarnation());
        assertEquals(SupervisorChildRegistry.Phase.ACTIVE, first.phase());

        assertThrows(IllegalStateException.class, () ->
                registry.beginRestart(first.child(), firstActor, 1),
                "cannot reserve replacement before physical-finalization admission");
        registry.finalized(first.child(), firstActor, 1);
        var reservation = registry.beginRestart(first.child(), firstActor, 1);
        assertEquals(SupervisorChildRegistry.Phase.RESTARTING,
                registry.snapshot(first.child()).phase());
        assertThrows(IllegalStateException.class,
                () -> registry.beginRestart(first.child(), firstActor, 1),
                "a competing restart must not be allowed to spawn");
        assertThrows(IllegalStateException.class,
                () -> registry.stop(first.child(), firstActor, 1),
                "STOP cannot overtake an active restart reservation");
        var nextActor = ActorRuntime.ActorId.create();
        var next = registry.replace(reservation, nextActor);
        assertThrows(IllegalStateException.class,
                () -> registry.replace(reservation, ActorRuntime.ActorId.create()),
                "committed reservation cannot replay");
        assertEquals(first.child(), next.child(), "logical identity must remain stable");
        assertNotEquals(first.actor(), next.actor(), "replacement needs a fresh ActorId");
        assertEquals(2, next.incarnation());
        assertEquals(SupervisorChildRegistry.Phase.ACTIVE, next.phase());
        assertThrows(IllegalStateException.class,
                () -> registry.finalized(first.child(), firstActor, 1),
                "a stale finalizer must not finalize the replacement");
        assertThrows(IllegalStateException.class, () -> registry.stop(
                first.child(), firstActor, 1),
                "an old failure cannot stop the new actor incarnation");

        registry.finalized(next.child(), nextActor, 2);
        registry.stop(next.child(), nextActor, 2);
        assertEquals(SupervisorChildRegistry.Phase.STOPPED,
                registry.snapshot(next.child()).phase());
        assertThrows(IllegalStateException.class, () ->
                registry.beginRestart(next.child(), nextActor, 2));
        registry.retireStopped(next.child());
        assertEquals(0, registry.trackedChildren());
        assertThrows(IllegalStateException.class,
                () -> registry.snapshot(next.child()),
                "retired child IDs may never reactivate");
    }

    @Test
    void rejectsCrossSupervisorIdentityAndDuplicateActiveActor() {
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        var registry = new SupervisorChildRegistry(supervisor, group);
        var foreign = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actor = ActorRuntime.ActorId.create();
        var first = registry.register(actor);
        assertThrows(IllegalStateException.class, () -> registry.register(actor));
        assertThrows(SecurityException.class,
                () -> foreign.snapshot(first.child()));
        // Two registry instances for one owner still mint disjoint UUIDs;
        // old child keys cannot address a newly created registry (ABA).
        var replacementRegistry = new SupervisorChildRegistry(supervisor, group);
        var otherInstance = replacementRegistry.register(ActorRuntime.ActorId.create());
        assertNotEquals(first.child(), otherInstance.child());
        assertThrows(IllegalStateException.class,
                () -> registry.snapshot(otherInstance.child()));
        assertThrows(IllegalStateException.class,
                () -> replacementRegistry.snapshot(first.child()));
        assertThrows(IllegalStateException.class,
                () -> registry.finalized(first.child(), ActorRuntime.ActorId.create(), 1));

        registry.finalized(first.child(), actor, 1);
        var permit = registry.beginRestart(first.child(), actor, 1);
        assertThrows(IllegalStateException.class,
                () -> registry.replace(permit, actor),
                "same ActorId must not masquerade as replacement");
        var other = registry.register(ActorRuntime.ActorId.create());
        assertThrows(IllegalStateException.class,
                () -> registry.replace(permit, other.actor()),
                "cannot steal an active actor belonging to another logical child");
        registry.abortRestart(permit);
        assertThrows(IllegalStateException.class,
                () -> registry.replace(permit, ActorRuntime.ActorId.create()),
                "aborted reservation must never reactivate");
    }

    @Test
    void failedRestartAbortRestoresFinalizedButFencesOldPermit() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var oldActor = ActorRuntime.ActorId.create();
        var first = registry.register(oldActor);
        registry.finalized(first.child(), oldActor, 1);
        var previous = registry.beginRestart(first.child(), oldActor, 1);
        assertThrows(IllegalStateException.class, () ->
                registry.beginRestart(first.child(), oldActor, 1));
        registry.abortRestart(previous);
        assertEquals(SupervisorChildRegistry.Phase.FINALIZED,
                registry.snapshot(first.child()).phase());
        var current = registry.beginRestart(first.child(), oldActor, 1);
        assertNotEquals(previous.nonce(), current.nonce());
        assertThrows(IllegalStateException.class, () ->
                registry.abortRestart(previous));
        assertThrows(IllegalStateException.class, () ->
                registry.replace(previous, ActorRuntime.ActorId.create()));
        var replacement = ActorRuntime.ActorId.create();
        registry.replace(current, replacement);
        assertEquals(2, registry.snapshot(first.child()).incarnation());
        registry.finalized(first.child(), replacement, 2);
        var reuse = registry.beginRestart(first.child(), replacement, 2);
        assertThrows(IllegalStateException.class,
                () -> registry.replace(reuse, oldActor),
                "A -> B -> A actor ABA must not be accepted");
        registry.abortRestart(reuse);
        assertEquals(2, registry.trackedActorIdentities());
    }

    @Test
    void admissionRollbackDoesNotRecycleRetiredActorIdentity() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actor = ActorRuntime.ActorId.create();
        var admitted = registry.register(actor);
        assertThrows(IllegalStateException.class,
                () -> registry.detachActive(admitted.child(), ActorRuntime.ActorId.create(), 1));
        registry.detachActive(admitted.child(), actor, 1);
        assertEquals(0, registry.trackedChildren());
        assertEquals(1, registry.trackedActorIdentities(),
                "rollback must preserve actor-identity replay fence");
        assertThrows(IllegalStateException.class, () -> registry.register(actor));
        assertThrows(IllegalStateException.class,
                () -> registry.finalized(admitted.child(), actor, 1));
        assertThrows(IllegalStateException.class,
                () -> registry.detachActive(admitted.child(), actor, 1));
    }

    @Test
    void reservedStopExcludesRestartAndStaleAcknowledgments() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actor = ActorRuntime.ActorId.create();
        var first = registry.register(actor);
        registry.finalized(first.child(), actor, 1);

        var stop = registry.beginStop(first.child(), actor, 1, 17);
        assertEquals(SupervisorChildRegistry.Phase.STOPPING,
                registry.snapshot(first.child()).phase());
        assertThrows(IllegalStateException.class, () ->
                registry.beginRestart(first.child(), actor, 1));
        assertThrows(IllegalStateException.class, () ->
                registry.beginStop(first.child(), actor, 1, 18));
        assertThrows(IllegalStateException.class, () ->
                registry.stop(first.child(), actor, 1));
        var wrong = new SupervisorChildRegistry.StopReservation(
                first.child(), actor, 1, 17, java.util.UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> registry.commitStop(wrong));
        assertEquals(SupervisorChildRegistry.Phase.STOPPING,
                registry.snapshot(first.child()).phase(),
                "a forged acknowledgement must not release the reservation");

        registry.markStopApplied(stop);
        assertThrows(IllegalStateException.class, () -> registry.markStopUncertain(stop),
                "a verified host effect must never be downgraded to uncertain");
        registry.commitStop(stop);
        assertEquals(SupervisorChildRegistry.Phase.STOPPED,
                registry.snapshot(first.child()).phase());
        assertThrows(IllegalStateException.class, () -> registry.commitStop(stop));
        assertThrows(IllegalStateException.class, () ->
                registry.beginRestart(first.child(), actor, 1));
    }

    @Test
    void concurrentStopAndRestartReservationsHaveExactlyOneWinner() throws Exception {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actor = ActorRuntime.ActorId.create();
        var first = registry.register(actor);
        registry.finalized(first.child(), actor, 1);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var stopTask = pool.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("race timed out");
                    registry.beginStop(first.child(), actor, 1, 19);
                    successes.incrementAndGet();
                } catch (IllegalStateException expected) {
                    failures.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            var restartTask = pool.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("race timed out");
                    registry.beginRestart(first.child(), actor, 1);
                    successes.incrementAndGet();
                } catch (IllegalStateException expected) {
                    failures.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            stopTask.get(5, java.util.concurrent.TimeUnit.SECONDS);
            restartTask.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1, successes.get(), "STOP and RESTART must never both own a turn");
        assertEquals(1, failures.get());
        assertTrue(registry.snapshot(first.child()).phase()
                == SupervisorChildRegistry.Phase.STOPPING
                || registry.snapshot(first.child()).phase()
                == SupervisorChildRegistry.Phase.RESTARTING);
    }

    @Test
    void concurrentReplacementOfSameGenerationHasSingleWinner() throws Exception {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actor = ActorRuntime.ActorId.create();
        var first = registry.register(actor);
        registry.finalized(first.child(), actor, 1);

        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var success = new java.util.concurrent.atomic.AtomicInteger();
        var failed = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i ->
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                throw new IllegalStateException("race barrier timed out");
                            var permit = registry.beginRestart(first.child(), actor, 1);
                            registry.replace(permit, ActorRuntime.ActorId.create());
                            success.incrementAndGet();
                        } catch (IllegalStateException expected) {
                            failed.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    })).toList();
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (var task : tasks) task.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1, success.get(), "only one replacement can win generation 1");
        assertEquals(1, failed.get());
        assertEquals(2, registry.snapshot(first.child()).incarnation());
    }
}
