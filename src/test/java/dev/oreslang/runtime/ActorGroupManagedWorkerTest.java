package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Managed workers are a lifecycle opt-in, not an implicit membership right. */
@Timeout(15)
final class ActorGroupManagedWorkerTest {

    @Test
    void spawnWorkerRetainsRefAndReadinessReturnsTheSameRef() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> worker = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> (message, actorContext) -> { });

            assertEquals(1, group.workerCount());
            assertSame(worker, group.workers().getFirst());
            assertFalse(group.contains(worker), "ownership must not silently confer event-bus membership");
            assertFalse(worker.readyRef().cancel(false), "readiness observation cannot cancel the actor");
            assertSame(worker, worker.readyRef().get(5, TimeUnit.SECONDS));
            assertEquals(1, group.workersReady().get(5, TimeUnit.SECONDS).size());
            assertTrue(worker.isAlive());

            worker.stop();
            worker.done().get(5, TimeUnit.SECONDS);
            assertEquals(0, group.workerCount(), "finalized refs must be evicted");
        }
    }

    @Test
    void groupCloseCancelsOnlyManagedWorkersNotOrdinaryMembers() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> worker = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> (message, actorContext) -> { });
            ActorRuntime.ActorRef<Integer> memberOnly = runtime.spawnPrivate(
                    context -> (message, actorContext) -> { });
            group.join(memberOnly, group.joinCapability());

            worker.ready().get(5, TimeUnit.SECONDS);
            memberOnly.ready().get(5, TimeUnit.SECONDS);
            assertEquals(1, group.workerCount());
            assertEquals(1, group.memberCount());

            group.close();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(worker.isAlive());
            assertTrue(memberOnly.isAlive(), "ordinary cooperating member must not be cancelled");
            assertEquals(0, group.workerCount());

            memberOnly.stop();
            memberOnly.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void workerStartupFailureFailsReadinessAndEvictsManagedRef() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> worker = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> { throw new IllegalStateException("start failed"); });
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> worker.readyRef().get(5, TimeUnit.SECONDS));
            assertEquals("start failed", failure.getCause().getMessage());
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, group.workerCount());
        }
    }

    @Test
    void structuredManagementIsExclusiveAcrossGroupsAndIdempotentWithinOneGroup()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup owner = runtime.createActorGroup();
            ActorRuntime.ActorGroup unrelated = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> worker = owner.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> (message, actorContext) -> { });

            assertSame(worker, owner.manageWorker(worker),
                    "registering the same worker twice in its owning group is idempotent");
            assertEquals(1, owner.workerCount());
            assertThrows(IllegalStateException.class, () -> unrelated.manageWorker(worker),
                    "a second cooperation group must not acquire lifecycle ownership");
            assertEquals(0, unrelated.workerCount());

            worker.getReady().get(5, TimeUnit.SECONDS);
            unrelated.close();
            assertTrue(worker.isAlive(), "closing a non-owner cannot cancel the worker");

            owner.close();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(worker.isAlive());
        }
    }

    @Test
    void staleSameIdRefCannotJoinManageOrReleaseLiveIncarnation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            ActorRuntime.ActorRef<String> live = runtime.spawnShared(
                    () -> (message, context) -> { });

            var constructor = live.getClass().getDeclaredConstructor(
                    ActorRuntime.class,
                    ActorRuntime.ActorId.class,
                    ActorRuntime.ActorKind.class,
                    long.class);
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            ActorRuntime.ActorRef<String> stale =
                    (ActorRuntime.ActorRef<String>) constructor.newInstance(
                            runtime, live.id(), live.kind(), live.generation() + 1L);

            assertThrows(IllegalStateException.class,
                    () -> group.join(stale, capability));
            assertFalse(group.contains(stale),
                    "membership queries must not alias a same-id stale ref");
            assertThrows(IllegalStateException.class,
                    () -> group.manageWorker(stale));

            assertSame(live, group.manageWorker(live));
            assertThrows(IllegalStateException.class,
                    () -> group.releaseWorker(stale),
                    "a stale ref must not release lifecycle ownership of the live worker");
            assertEquals(1, group.workerCount());

            group.close();
            assertTrue(live.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void staleSameIdTerminationCannotEvictLiveMembership() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            ActorRuntime.ActorRef<String> live = runtime.spawnShared(
                    () -> (message, context) -> { });
            group.join(live, capability);
            assertTrue(group.contains(live));
            assertEquals(1, group.memberCount());

            var constructor = live.getClass().getDeclaredConstructor(
                    ActorRuntime.class,
                    ActorRuntime.ActorId.class,
                    ActorRuntime.ActorKind.class,
                    long.class);
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            ActorRuntime.ActorRef<String> stale =
                    (ActorRuntime.ActorRef<String>) constructor.newInstance(
                            runtime, live.id(), live.kind(), live.generation() + 1L);

            var terminated = group.getClass().getDeclaredMethod(
                    "actorTerminated", ActorRuntime.ActorRef.class);
            terminated.setAccessible(true);
            terminated.invoke(group, stale);

            assertTrue(group.contains(live),
                    "stale same-id retirement must not revoke the live member");
            assertEquals(1, group.memberCount(),
                    "stale same-id retirement must not release live membership quota");

            group.close();
            live.stop();
            live.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void stopAdmissionLedgerUsesExactActorRefNotLogicalId() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            java.util.concurrent.CountDownLatch entered =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release =
                    new java.util.concurrent.CountDownLatch(1);

            ActorRuntime.ActorRef<String> live = runtime.spawnShared(
                    () -> (message, context) -> {
                        entered.countDown();
                        release.await(5, TimeUnit.SECONDS);
                    });
            group.manageWorker(live);
            live.send("hold");
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            assertEquals(1, group.cancelWorkers());

            var field = group.getClass().getDeclaredField("stoppingWorkers");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Set<Object> stopping =
                    (java.util.Set<Object>) field.get(group);
            assertTrue(stopping.contains(live),
                    "stop admission must retain the exact ActorRef incarnation");
            assertFalse(stopping.contains(live.id()),
                    "logical ActorId must not be the stop-admission authority key");

            release.countDown();
            assertTrue(live.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void reverseMembershipIndexUsesExactActorRefIdentity() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> live = runtime.spawnShared(
                    () -> (message, context) -> { });
            group.join(live, group.joinCapability());

            var field = ActorRuntime.class.getDeclaredField("groupMemberships");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<Object, ?> memberships =
                    (java.util.Map<Object, ?>) field.get(runtime);
            assertTrue(memberships.containsKey(live),
                    "reverse membership quota must be scoped to the exact ActorRef");
            assertFalse(memberships.containsKey(live.id()),
                    "logical ActorId must not merge membership quotas across generations");

            group.close();
            live.stop();
            live.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void managementRejectsCrossRuntimeRefsAndRequiresActiveActor() throws Exception {
        try (ActorRuntime one = new ActorRuntime();
             ActorRuntime two = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = one.createActorGroup();
            ActorRuntime.ActorRef<String> foreign = two.spawnPrivate(
                    context -> (message, actorContext) -> { });
            assertThrows(IllegalArgumentException.class, () -> group.manageWorker(foreign));
            assertEquals(0, group.workerCount());
            foreign.stop();
            foreign.done().get(5, TimeUnit.SECONDS);

            ActorRuntime.ActorRef<Integer> completed = one.spawnPrivate(
                    context -> (message, actorContext) -> { });
            completed.stop();
            completed.done().get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> group.manageWorker(completed));
        }
    }
}
