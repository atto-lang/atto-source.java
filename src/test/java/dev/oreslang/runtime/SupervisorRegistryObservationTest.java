package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the REAL ActorRuntime supervisor registry and a host-only, partial
 * observation bridge. NOT proof of native CONTROL admission or guest UI.
 */
@Timeout(15)
final class SupervisorRegistryObservationTest {
    private static final String TENANT = "t-one";
    private static final Instant FIXED = Instant.parse("2026-10-31T12:00:00Z");

    private static SupervisorRegistryObservation.Grant grant(
            SupervisorRegistryObservation bridge, Object session,
            ActorRuntime.ActorSupervisor scope) {
        return bridge.issueGrant(session, TENANT, scope, TimeUnit.SECONDS.toNanos(30));
    }

    private static SupervisorRegistryObservation.Snapshot capture(
            SupervisorRegistryObservation bridge,
            SupervisorRegistryObservation.Grant grant, Object session) {
        return bridge.capture(grant, session, TENANT, 1,
                32, 16, TimeUnit.SECONDS.toNanos(1));
    }

    @Test
    void observesActualSupervisorHierarchyWithoutInventingActorHealth() {
        try (var runtime = new ActorRuntime();
             var bridge = new SupervisorRegistryObservation(runtime)) {
            var root = runtime.rootSupervisor();
            var s = runtime.createChildSupervisor(root);
            var c = runtime.createChildSupervisor(s);
            var other = runtime.createChildSupervisor(root);
            Object session = new Object();
            var result = capture(bridge, grant(bridge, session, s), session);
            assertEquals("partial", result.status());
            assertEquals(1, result.schemaVersion());
            assertEquals(s.id().value().toString(), result.scopeRootId());
            assertEquals(Set.of(s.id().value().toString(), c.id().value().toString()),
                    result.supervisors().stream().map(
                            SupervisorRegistryObservation.SupervisorNode::id)
                            .collect(java.util.stream.Collectors.toSet()));
            assertEquals(s.id().value().toString(), result.supervisors().get(0).id());
            assertNull(result.supervisors().get(0).parentId(),
                    "scoped root must not leak the enclosing supervisor");
            assertFalse(result.supervisors().stream()
                    .anyMatch(n -> n.id().equals(other.id().value().toString())));
            assertTrue(result.actors().isEmpty(),
                    "no actor status is made up from group/registry relationships");
            assertTrue(result.warnings().contains("SUPERVISOR_REGISTRY_ONLY_NO_CONTROL"));
            assertEquals("observed", result.supervisors().get(0).status());
            assertEquals(1L, result.supervisors().get(0).generation());
        }
    }

    @Test
    void hostGrantCannotBeReusedByAnotherSessionTenantOrGeneration() {
        try (var runtime = new ActorRuntime();
             var bridge = new SupervisorRegistryObservation(runtime)) {
            Object session = new Object();
            var grant = grant(bridge, session, runtime.rootSupervisor());
            assertThrows(SecurityException.class, () ->
                    bridge.capture(grant, new Object(), TENANT, 1, 32, 16, 100_000));
            assertThrows(SecurityException.class, () ->
                    bridge.capture(grant, session, "t-other", 1, 32, 16, 100_000));
            assertThrows(SecurityException.class, () ->
                    bridge.capture(grant, session, TENANT, 2, 32, 16, 100_000));
            assertThrows(SecurityException.class, () ->
                    bridge.capture(null, session, TENANT, 1, 32, 16, 100_000));
            bridge.revoke(grant);
            assertThrows(SecurityException.class, () -> capture(bridge, grant, session));
        }
    }

    @Test
    void foreignSupervisorAndForeignBridgeGrantFailBeforeObservation() {
        try (var a = new ActorRuntime();
             var b = new ActorRuntime();
             var bridgeA = new SupervisorRegistryObservation(a);
             var bridgeB = new SupervisorRegistryObservation(b)) {
            Object session = new Object();
            assertThrows(SecurityException.class, () ->
                    bridgeA.issueGrant(session, TENANT, b.rootSupervisor(), 100_000));
            var bGrant = grant(bridgeB, session, b.rootSupervisor());
            assertThrows(SecurityException.class, () ->
                    bridgeA.capture(bGrant, session, TENANT, 1, 32, 16, 100_000));
        }
    }

    @Test
    void nodeAndDepthBudgetsProduceExplicitPartialResults() {
        try (var runtime = new ActorRuntime();
             var bridge = new SupervisorRegistryObservation(runtime)) {
            var root = runtime.rootSupervisor();
            var s = runtime.createChildSupervisor(root);
            runtime.createChildSupervisor(s);
            Object session = new Object();
            var grant = grant(bridge, session, root);
            var nodes = bridge.capture(grant, session, TENANT, 1, 1, 16,
                    TimeUnit.SECONDS.toNanos(1));
            assertEquals(1, nodes.supervisors().size());
            assertFalse(nodes.missingSupervisorIds().isEmpty());
            assertTrue(nodes.warnings().contains("BUDGET_EXHAUSTED"));
            var depth = bridge.capture(grant, session, TENANT, 1, 32, 0,
                    TimeUnit.SECONDS.toNanos(1));
            assertEquals(1, depth.supervisors().size());
            assertTrue(depth.warnings().contains("DEPTH_EXHAUSTED"));
            assertFalse(depth.missingSupervisorIds().isEmpty());
            assertThrows(IllegalArgumentException.class, () ->
                    bridge.capture(grant, session, TENANT, 1, 0, 16, 1));
            assertThrows(IllegalArgumentException.class, () ->
                    bridge.capture(grant, session, TENANT, 1, 32, 65, 1));
        }
    }

    @Test
    void expiredGrantAndElapsedReadBudgetFailClosedUsingDeterministicClock() {
        AtomicLong tick = new AtomicLong(100);
        try (var runtime = new ActorRuntime();
             var bridge = new SupervisorRegistryObservation(runtime, tick::get, () -> FIXED)) {
            Object session = new Object();
            var root = runtime.rootSupervisor();
            var grant = bridge.issueGrant(session, TENANT, root, 10);
            tick.set(110);
            assertThrows(SecurityException.class, () -> capture(bridge, grant, session));
        }

        AtomicLong moving = new AtomicLong(100);
        try (var runtime = new ActorRuntime();
             var bridge = new SupervisorRegistryObservation(runtime,
                     () -> moving.getAndAdd(10), () -> FIXED)) {
            Object session = new Object();
            var root = runtime.rootSupervisor();
            runtime.createChildSupervisor(root);
            var grant = bridge.issueGrant(session, TENANT, root, 10_000);
            var result = bridge.capture(grant, session, TENANT, 1, 32, 16, 1);
            assertEquals("partial", result.status());
            assertTrue(result.warnings().contains("READ_DEADLINE_EXCEEDED"));
        }
    }

    @Test
    void ordinaryActorCannotMintOrUseSupervisorObservationGrant() throws Exception {
        try (var runtime = new ActorRuntime();
             var bridge = new SupervisorRegistryObservation(runtime)) {
            Object session = new Object();
            var root = runtime.rootSupervisor();
            var grant = grant(bridge, session, root);
            CountDownLatch tried = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            var actor = runtime.spawnPrivateTrusted(factory ->
                    (message, context) -> {
                        try {
                            bridge.issueGrant(session, TENANT, root, 100_000);
                            unexpected.set(new AssertionError("actor minted a grant"));
                        } catch (SecurityException expected) {
                            // Host-only substrate.
                        } catch (Throwable error) {
                            unexpected.set(error);
                        }
                        try {
                            capture(bridge, grant, session);
                            unexpected.set(new AssertionError("actor used privileged snapshot"));
                        } catch (SecurityException expected) {
                            // Host-only substrate.
                        } catch (Throwable error) {
                            unexpected.set(error);
                        } finally {
                            tried.countDown();
                        }
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send("attempt");
            assertTrue(tried.await(5, TimeUnit.SECONDS));
            assertNull(unexpected.get());
            actor.stop();
            actor.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void grantRevocationAndAdapterCloseAreHostOnlyAndIdempotent() {
        try (var runtime = new ActorRuntime()) {
            var bridge = new SupervisorRegistryObservation(runtime);
            Object session = new Object();
            var grant = grant(bridge, session, runtime.rootSupervisor());
            bridge.revoke(grant);
            bridge.revoke(grant);
            assertThrows(SecurityException.class, () -> capture(bridge, grant, session));
            bridge.close();
            bridge.close();
            assertThrows(IllegalStateException.class, () ->
                    bridge.issueGrant(session, TENANT, runtime.rootSupervisor(), 100_000));
        }
    }

    @Test
    void concurrentRegistryGrowthIsFlaggedInsteadOfClaimedGloballyAtomic() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch atObservation = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        try (var runtime = new ActorRuntime();
             var bridge = new SupervisorRegistryObservation(runtime, () -> {
                 int call = calls.incrementAndGet();
                 // The fourth clock read happens after the snapshot captured
                 // the registry, between scanning root and child.
                 if (call == 4) {
                     atObservation.countDown();
                     try {
                         if (!proceed.await(5, TimeUnit.SECONDS))
                             throw new AssertionError("concurrent observation never resumed");
                     } catch (InterruptedException e) {
                         Thread.currentThread().interrupt();
                         throw new AssertionError(e);
                     }
                 }
                 return call;
             }, () -> FIXED);
             var executor = Executors.newSingleThreadExecutor()) {
            var root = runtime.rootSupervisor();
            runtime.createChildSupervisor(root);
            Object session = new Object();
            var grant = bridge.issueGrant(session, TENANT, root, 10_000);
            Future<SupervisorRegistryObservation.Snapshot> captured =
                    executor.submit(() -> bridge.capture(grant, session, TENANT,
                            1, 32, 16, 1_000));
            assertTrue(atObservation.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () ->
                    bridge.capture(grant, session, TENANT, 1, 32, 16, 1_000));
            runtime.createChildSupervisor(root);
            proceed.countDown();
            var result = captured.get(5, TimeUnit.SECONDS);
            assertEquals("partial", result.status());
            assertTrue(result.warnings().contains(
                    "REGISTRY_CHANGED_DURING_OBSERVATION"));
        }
    }

    @Test
    void observationDoesNotReadGuestActorPayloadOrReportHealthyActors() throws Exception {
        try (var runtime = new ActorRuntime();
             var bridge = new SupervisorRegistryObservation(runtime)) {
            var supervisor = runtime.createSupervisor();
            var group = runtime.createActorGroup(supervisor);
            var ref = supervisor.startChildHost(group,
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    factory -> (message, context) -> { });
            ref.getReady().get(5, TimeUnit.SECONDS);
            ref.send("password-in-mailbox");
            Object session = new Object();
            var captured = capture(bridge, grant(bridge, session, supervisor), session);
            assertEquals("partial", captured.status());
            assertTrue(captured.actors().isEmpty());
            assertFalse(captured.toString().contains("password-in-mailbox"));
            assertTrue(captured.warnings().contains("ACTOR_LIFECYCLE_COVERAGE_UNAVAILABLE"));
            group.close();
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
