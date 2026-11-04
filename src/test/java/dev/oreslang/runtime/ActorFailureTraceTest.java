package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-substrate tests, not source-compiled .atto guest exception proof and
 * not proof of actual supervisor CONTROL receipt delivery.
 */
@Timeout(20)
final class ActorFailureTraceTest {
    @Test
    void detachedValueSnapshotIsBoundedDefensiveAndDoesNotInventStackFrames() {
        UUID actor = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        UUID supervisor = UUID.randomUUID();
        List<UUID> parents = new ArrayList<>(List.of(parent));
        List<UUID> supervisors = new ArrayList<>(List.of(supervisor));
        var snapshot = ActorFailureTrace.capture(
                ActorFailureTrace.FailureKind.UNCLASSIFIED, actor, 7,
                parents, supervisors, false, true);
        parents.clear();
        supervisors.clear();

        assertEquals("atto.actor-failure-causality.v1", snapshot.schema());
        assertEquals(3, snapshot.boundaries().size());
        assertEquals(actor.toString(), snapshot.boundaries().get(0).identity());
        assertEquals(parent.toString(), snapshot.boundaries().get(1).identity());
        assertEquals(supervisor.toString(), snapshot.boundaries().get(2).identity());
        assertEquals(7, snapshot.boundaries().get(0).actorGeneration());
        assertEquals(0, snapshot.boundaries().get(1).actorGeneration());
        assertFalse(snapshot.truncated());
        assertFalse(snapshot.sourceFramesAvailable());
        assertFalse(snapshot.supervisorControlDelivered());
        assertThrows(UnsupportedOperationException.class, snapshot.boundaries()::clear);
        // Custom guest-facing collections are rejected before any accessor is invoked.
        var forged = new java.util.LinkedList<ActorFailureTrace.Boundary>();
        forged.add(snapshot.boundaries().get(0));
        assertThrows(IllegalArgumentException.class, () ->
                new ActorFailureTrace.Snapshot(
                        ActorFailureTrace.FailureKind.UNCLASSIFIED,
                        forged, false, false));
        assertThrows(IllegalArgumentException.class, () -> ActorFailureTrace.capture(
                ActorFailureTrace.FailureKind.CANCELLED, actor, 1,
                java.util.Collections.nCopies(ActorFailureTrace.MAX_PARENT_ACTORS + 1, parent),
                List.of(), true, false));
        assertThrows(IllegalArgumentException.class, () -> ActorFailureTrace.capture(
                ActorFailureTrace.FailureKind.UNCLASSIFIED, actor, 1,
                List.of(), List.of(supervisor), false, false));
    }

    @Test
    void capturesOnlyTrustedHostLineageBeforeActorArenaRetirement() throws Exception {
        ActorFailureTrace.Snapshot detached;
        UUID actorId;
        UUID leafId;
        UUID parentId;
        UUID rootId;
        try (ActorRuntime runtime = new ActorRuntime()) {
            var root = runtime.rootSupervisor();
            var parent = runtime.createChildSupervisor(root);
            var leaf = runtime.createChildSupervisor(parent);
            var group = runtime.createActorGroup(leaf);
            var failed = leaf.<String>startChildHost(
                    group, ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    ignored -> (message, context) -> {
                        throw new IllegalArgumentException("guest secret must not cross");
                    });
            failed.getReady().get(5, TimeUnit.SECONDS);
            failed.send("fail");
            assertTrue(failed.awaitTermination(5, TimeUnit.SECONDS));
            actorId = failed.id().value();
            leafId = leaf.id().value();
            parentId = parent.id().value();
            rootId = root.id().value();

            detached = failed.failureTrace().orElseThrow();
            assertEquals(ActorFailureTrace.FailureKind.UNCLASSIFIED, detached.failureKind());
            assertEquals(List.of(
                    ActorFailureTrace.BoundaryKind.ACTOR_TERMINAL,
                    ActorFailureTrace.BoundaryKind.SUPERVISOR_ASSOCIATION,
                    ActorFailureTrace.BoundaryKind.SUPERVISOR_ASSOCIATION,
                    ActorFailureTrace.BoundaryKind.SUPERVISOR_ASSOCIATION),
                    detached.boundaries().stream().map(ActorFailureTrace.Boundary::kind).toList());
            assertEquals(List.of(actorId.toString(), leafId.toString(),
                    parentId.toString(), rootId.toString()),
                    detached.boundaries().stream().map(ActorFailureTrace.Boundary::identity).toList());
            assertFalse(detached.truncated());
            assertTrue(detached.supervisorAssociationKnown());
            assertFalse(detached.supervisorControlDelivered());
            group.close();
        }
        // No actor memory slice, supervisor registry, guest Throwable or
        // carrier remains available; the detached data is still stable.
        assertEquals(4, detached.boundaries().size());
        assertFalse(detached.toString().contains("guest secret"));
    }

    @Test
    void actorCannotGainSupervisorInspectionByHoldingFailedRef() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var failed = runtime.<String>spawnShared(
                    ignored -> (message, context) -> { throw new IllegalStateException("fail"); });
            failed.getReady().get(5, TimeUnit.SECONDS);
            failed.send("go");
            assertTrue(failed.awaitTermination(5, TimeUnit.SECONDS));
            var denied = new AtomicReference<Throwable>();
            var done = new CountDownLatch(1);
            var inspector = runtime.<String>spawnPrivateTrusted(
                    ignored -> (message, context) -> {
                        try {
                            failed.failureTrace();
                            denied.set(new AssertionError("actor inspected supervisor-only trace"));
                        } catch (SecurityException expected) {
                            // No inheritance of CONTROL authority across actor heaps.
                        } catch (Throwable unexpected) {
                            denied.set(unexpected);
                        }
                        try {
                            failed.failure();
                            denied.set(new AssertionError("actor received raw child Throwable"));
                        } catch (SecurityException expected) {
                            // A child Throwable and its suppressed frames must
                            // never escape its actor's ownership boundary.
                        } catch (Throwable unexpected) {
                            denied.set(unexpected);
                        } finally {
                            done.countDown();
                        }
                    });
            inspector.getReady().get(5, TimeUnit.SECONDS);
            inspector.send("inspect");
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(denied.get());
            inspector.stop();
            assertTrue(inspector.awaitTermination(5, TimeUnit.SECONDS));
        }
    }


    @Test
    void cleanupOnlyFirstWinnerAlsoCapturesDetachedTrace() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            runtime.setActorExitHook(ignored -> {
                throw new IllegalStateException("host cleanup failed");
            });
            var actor = runtime.<String>spawnShared(
                    ignored -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isPresent(), "cleanup failure must be visible");
            var snapshot = actor.failureTrace().orElseThrow();
            assertEquals(actor.id().value().toString(),
                    snapshot.boundaries().get(0).identity());
            assertEquals(actor.generation(),
                    snapshot.boundaries().get(0).actorGeneration());
            assertFalse(snapshot.supervisorControlDelivered());
            assertFalse(snapshot.sourceFramesAvailable());
            assertFalse(snapshot.toString().contains("host cleanup failed"));
        }
    }

    @Test
    void supervisorDepthTruncationIsHonestAndNeverClaimsControlDelivery() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.rootSupervisor();
            for (int i = 0; i < 12; i++) {
                parent = runtime.createChildSupervisor(parent);
            }
            var group = runtime.createActorGroup(parent);
            var failed = parent.<String>startChildHost(
                    group, ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    ignored -> { throw new IllegalStateException("failed initialization"); });
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> failed.getReady().get(5, TimeUnit.SECONDS));
            assertTrue(failed.awaitTermination(5, TimeUnit.SECONDS));
            var snapshot = failed.failureTrace().orElseThrow();
            assertEquals(1 + ActorFailureTrace.MAX_SUPERVISORS,
                    snapshot.boundaries().size());
            assertTrue(snapshot.truncated());
            assertFalse(snapshot.supervisorControlDelivered());
            group.close();
        }
    }
}
