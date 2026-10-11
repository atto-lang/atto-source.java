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

        assertThrows(IllegalStateException.class, () -> registry.replace(
                first.child(), firstActor, 1, ActorRuntime.ActorId.create()),
                "cannot replace before physical-finalization admission");
        registry.finalized(first.child(), firstActor, 1);
        var nextActor = ActorRuntime.ActorId.create();
        var next = registry.replace(first.child(), firstActor, 1, nextActor);
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
        assertThrows(IllegalStateException.class, () -> registry.replace(
                next.child(), nextActor, 2, ActorRuntime.ActorId.create()));
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
        assertThrows(IllegalStateException.class,
                () -> registry.replace(first.child(), actor, 1, actor),
                "same ActorId must not masquerade as replacement");
        var other = registry.register(ActorRuntime.ActorId.create());
        assertThrows(IllegalStateException.class,
                () -> registry.replace(first.child(), actor, 1, other.actor()),
                "cannot steal an active actor belonging to another logical child");
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
                            registry.replace(first.child(), actor, 1,
                                    ActorRuntime.ActorId.create());
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
