package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorGroupPlacementPolicyTest {
    @Test
    void defaultGroupAndExplicitUnrestrictedPolicyRetainLegacyBehavior() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup legacy = runtime.createActorGroup();
            ActorGroupPlacementPolicy unrestricted = ActorGroupPlacementPolicy.unrestricted();
            ActorRuntime.ActorGroup explicit = runtime.createActorGroup(unrestricted);

            assertEquals(unrestricted, legacy.placementPolicy());
            assertEquals(unrestricted, explicit.placementPolicy());
            assertNotEquals(legacy.id(), explicit.id());
            assertEquals(2, runtime.actorGroupCount());

            legacy.close();
            assertTrue(legacy.closed());
            assertFalse(explicit.closed());
            explicit.close();
            assertEquals(0, runtime.actorGroupCount());
        }
    }

    @Test
    void unsupportedSchedulerAndGpuClaimsFailBeforeAllocatingAnyGroup() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var defaultPolicy = ActorGroupPlacementPolicy.unrestricted();
            assertThrows(NullPointerException.class, () -> runtime.createActorGroup(null));
            assertEquals(0, runtime.actorGroupCount());

            assertThrows(UnsupportedOperationException.class, () -> runtime.createActorGroup(
                    new ActorGroupPlacementPolicy(
                            ActorGroupPlacementPolicy.CpuAffinity.PREFER_SAME_CORE,
                            Integer.MAX_VALUE,
                            ActorGroupPlacementPolicy.Accelerator.CPU_ONLY, null, 0)));
            assertThrows(UnsupportedOperationException.class, () -> runtime.createActorGroup(
                    new ActorGroupPlacementPolicy(
                            ActorGroupPlacementPolicy.CpuAffinity.PREFER_SAME_NUMA_NODE,
                            Integer.MAX_VALUE,
                            ActorGroupPlacementPolicy.Accelerator.CPU_ONLY, null, 0)));
            assertThrows(UnsupportedOperationException.class, () -> runtime.createActorGroup(
                    new ActorGroupPlacementPolicy(
                            ActorGroupPlacementPolicy.CpuAffinity.ANY, 1,
                            ActorGroupPlacementPolicy.Accelerator.CPU_ONLY, null, 0)));
            assertThrows(UnsupportedOperationException.class, () -> runtime.createActorGroup(
                    new ActorGroupPlacementPolicy(
                            ActorGroupPlacementPolicy.CpuAffinity.ANY, Integer.MAX_VALUE,
                            ActorGroupPlacementPolicy.Accelerator.GPU_PREFERRED, "gpu0", 2)));
            assertThrows(UnsupportedOperationException.class, () -> runtime.createActorGroup(
                    new ActorGroupPlacementPolicy(
                            ActorGroupPlacementPolicy.CpuAffinity.ANY, Integer.MAX_VALUE,
                            ActorGroupPlacementPolicy.Accelerator.GPU_REQUIRED, "gpu0", 2)));
            assertEquals(0, runtime.actorGroupCount(),
                    "rejected policies must not reserve IDs, groups or memberships");
            assertEquals(defaultPolicy, runtime.createActorGroup().placementPolicy(),
                    "rejected admission cannot poison a later default group");
        }
    }

    @Test
    void invalidPolicyValuesRejectAtConstruction() {
        var cpu = ActorGroupPlacementPolicy.CpuAffinity.ANY;
        var accel = ActorGroupPlacementPolicy.Accelerator.CPU_ONLY;
        assertThrows(NullPointerException.class,
                () -> new ActorGroupPlacementPolicy(null, 1, accel, null, 0));
        assertThrows(NullPointerException.class,
                () -> new ActorGroupPlacementPolicy(cpu, 1, null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGroupPlacementPolicy(cpu, 0, accel, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGroupPlacementPolicy(cpu, -1, accel, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGroupPlacementPolicy(cpu, 1, accel, null, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGroupPlacementPolicy(cpu, 1, accel, "gpu0", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGroupPlacementPolicy(cpu, 1, accel, null, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGroupPlacementPolicy(cpu, 1,
                        ActorGroupPlacementPolicy.Accelerator.GPU_REQUIRED, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGroupPlacementPolicy(cpu, 1,
                        ActorGroupPlacementPolicy.Accelerator.GPU_REQUIRED, "gpu0", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGroupPlacementPolicy(cpu, 1,
                        ActorGroupPlacementPolicy.Accelerator.GPU_REQUIRED, "../../bad", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGroupPlacementPolicy(cpu, 1,
                        ActorGroupPlacementPolicy.Accelerator.GPU_REQUIRED, "", 1));
    }

    @Test
    void placementCombinationMatrixNeverAdmitsAnUnenforcedPromise() {
        int accepted = 0;
        int rejected = 0;
        for (var affinity : ActorGroupPlacementPolicy.CpuAffinity.values()) {
            for (var accelerator : ActorGroupPlacementPolicy.Accelerator.values()) {
                for (int parallelism : new int[] { 1, 4, Integer.MAX_VALUE }) {
                    for (String device : new String[] { null, "gpu0", "../../invalid" }) {
                        for (int inFlight : new int[] { 0, 1, Integer.MAX_VALUE }) {
                            boolean validDevice = device == null || device.equals("gpu0");
                            boolean validFields = validDevice && (
                                    accelerator == ActorGroupPlacementPolicy.Accelerator.CPU_ONLY
                                            ? device == null && inFlight == 0
                                            : device != null && inFlight > 0);
                            if (!validFields) {
                                assertThrows(IllegalArgumentException.class,
                                        () -> new ActorGroupPlacementPolicy(
                                                affinity, parallelism, accelerator, device, inFlight));
                                rejected++;
                                continue;
                            }
                            var policy = new ActorGroupPlacementPolicy(
                                    affinity, parallelism, accelerator, device, inFlight);
                            boolean unrestricted =
                                    affinity == ActorGroupPlacementPolicy.CpuAffinity.ANY
                                    && accelerator == ActorGroupPlacementPolicy.Accelerator.CPU_ONLY
                                    && parallelism == Integer.MAX_VALUE;
                            if (unrestricted) {
                                assertDoesNotThrow(policy::requireSupported);
                                accepted++;
                            } else {
                                assertThrows(UnsupportedOperationException.class,
                                        policy::requireSupported);
                                rejected++;
                            }
                        }
                    }
                }
            }
        }
        assertEquals(1, accepted,
                "the sole admitted configuration is the legacy unrestricted policy");
        assertEquals(242, rejected, "all other combinations must reject");
    }

    @Test
    void concurrentUnsupportedAdmissionsCannotConsumeAnyGroupSlots() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var forbidden = new ActorGroupPlacementPolicy(
                    ActorGroupPlacementPolicy.CpuAffinity.ANY, 1,
                    ActorGroupPlacementPolicy.Accelerator.CPU_ONLY, null, 0);
            final int workers = 12;
            final int attemptsPerWorker = 64;
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(workers);
            java.util.concurrent.atomic.AtomicInteger rejected =
                    new java.util.concurrent.atomic.AtomicInteger();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            java.util.List<Thread> threads = new java.util.ArrayList<>();
            for (int i = 0; i < workers; i++) {
                Thread worker = new Thread(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("admission start timed out");
                        }
                        for (int j = 0; j < attemptsPerWorker; j++) {
                            try {
                                runtime.createActorGroup(forbidden);
                                throw new AssertionError("unsupported policy admitted");
                            } catch (UnsupportedOperationException expected) {
                                rejected.incrementAndGet();
                            }
                        }
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    } finally {
                        done.countDown();
                    }
                }, "group-policy-admission-" + i);
                threads.add(worker);
                worker.start();
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            for (Thread worker : threads) worker.join(2_000);
            assertNull(unexpected.get(), "unexpected concurrent policy admission result");
            assertEquals(workers * attemptsPerWorker, rejected.get());
            assertEquals(0, runtime.actorGroupCount());
            var valid = runtime.createActorGroup();
            assertEquals(1, runtime.actorGroupCount());
            valid.close();
            assertEquals(0, runtime.actorGroupCount());
        }
    }

    @Test
    void rejectedActorOwnedPoliciesDoNotLeakCreatorMembershipQuota() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var rejectedPolicy = new ActorGroupPlacementPolicy(
                    ActorGroupPlacementPolicy.CpuAffinity.PREFER_SAME_CORE,
                    Integer.MAX_VALUE,
                    ActorGroupPlacementPolicy.Accelerator.CPU_ONLY, null, 0);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            var creator = runtime.spawnSharedTrusted(
                    IsolatePolicy.developer(),
                    factoryContext -> (message, context) -> {
                        try {
                            for (int i = 0; i < 128; i++) {
                                assertThrows(UnsupportedOperationException.class,
                                        () -> context.runtime().createActorGroup(rejectedPolicy));
                            }
                            var admitted = context.runtime().createActorGroup();
                            assertEquals(ActorGroupPlacementPolicy.unrestricted(),
                                    admitted.placementPolicy());
                            assertEquals(1, context.runtime().actorGroupCount());
                            admitted.close();
                            assertEquals(0, context.runtime().actorGroupCount());
                        } catch (Throwable failure) {
                            error.set(failure);
                        } finally {
                            done.countDown();
                        }
                    });
            creator.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(error.get(), "unsupported creator policies must not consume actor membership slots");
            assertEquals(0, runtime.actorGroupCount());
            creator.stop();
            assertTrue(creator.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void placementInspectionIsMemberOrSupervisorOnly() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var join = group.joinCapability();
            var expected = ActorGroupPlacementPolicy.unrestricted();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            var actor = runtime.spawnSharedTrusted(
                    IsolatePolicy.developer(),
                    factoryContext -> (message, context) -> {
                        try {
                            assertThrows(SecurityException.class, group::placementPolicy);
                            join.join();
                            assertEquals(expected, group.placementPolicy());
                            group.leaveCurrent();
                            assertThrows(SecurityException.class, group::placementPolicy);
                        } catch (Throwable failure) {
                            unexpected.set(failure);
                        } finally {
                            done.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(unexpected.get());
            assertEquals(expected, group.placementPolicy(),
                    "trusted host supervisor can inspect policy");
            group.close();
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void foreignRuntimeActorCannotInspectOrCreateGroupsInAnotherRuntime() throws Exception {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime foreign = new ActorRuntime()) {
            var ownerGroup = owner.createActorGroup();
            CountDownLatch observed = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            var actor = foreign.spawnSharedTrusted(
                    IsolatePolicy.developer(),
                    factoryContext -> (message, context) -> {
                        try {
                            assertThrows(SecurityException.class, ownerGroup::placementPolicy);
                            assertThrows(SecurityException.class,
                                    () -> owner.createActorGroup(ActorGroupPlacementPolicy.unrestricted()));
                        } catch (Throwable problem) {
                            error.set(problem);
                        } finally {
                            observed.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(observed.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            assertEquals(1, owner.actorGroupCount(),
                    "foreign actor cannot allocate groups in a different runtime");
            ownerGroup.close();
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void groupMembershipRemainsOrthogonalToImmutablePlacementPolicy() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var groupA = runtime.createActorGroup(ActorGroupPlacementPolicy.unrestricted());
            var groupB = runtime.createActorGroup();
            var permitA = groupA.joinCapability();
            var permitB = groupB.joinCapability();
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            var actor = runtime.spawnSharedTrusted(
                    IsolatePolicy.developer(),
                    context -> (message, ctx) -> {
                        try {
                            permitA.join();
                            permitB.join();
                            assertEquals(ActorGroupPlacementPolicy.unrestricted(),
                                    groupA.placementPolicy());
                            assertEquals(ActorGroupPlacementPolicy.unrestricted(),
                                    groupB.placementPolicy());
                            groupA.leaveCurrent();
                            assertTrue(groupB.contains(ctx.self()));
                        } catch (Throwable failure) {
                            error.set(failure);
                        } finally {
                            finished.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            assertEquals(0, groupA.memberCount());
            assertEquals(1, groupB.memberCount());
            groupA.close();
            assertTrue(actor.isAlive(), "membership-only close cannot cancel an actor");
            groupB.close();
            assertTrue(actor.isAlive(), "placement does not change lifecycle authority");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
