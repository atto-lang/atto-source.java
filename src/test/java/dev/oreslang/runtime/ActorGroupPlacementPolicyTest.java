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
