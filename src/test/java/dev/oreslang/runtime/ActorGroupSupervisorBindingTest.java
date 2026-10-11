package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A supervision assignment is mandatory, immutable, runtime-affine and never
 * inferred from ActorGroup membership or managed-worker ownership.
 */
final class ActorGroupSupervisorBindingTest {

    @Test
    void defaultGroupIsImmediatelyRootSupervisedAndRemovedOnClose() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorSupervisor root = runtime.rootSupervisor();
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            assertEquals(root.id(), group.supervisorId());
            assertEquals(Set.of(group.id()), root.advisedGroupIds());

            group.close();
            assertTrue(root.advisedGroupIds().isEmpty());
            assertEquals(root.id(), group.supervisorId(),
                    "closed group must not silently reassign its supervisor");
        }
    }

    @Test
    void explicitSupervisorsHaveDisjointAndImmutableAdvisoryScopes() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorSupervisor left = runtime.createSupervisor();
            ActorRuntime.ActorSupervisor right = runtime.createSupervisor();
            ActorRuntime.ActorGroup a = runtime.createActorGroup(left);
            ActorRuntime.ActorGroup b = runtime.createActorGroup(right);
            ActorRuntime.ActorGroup c = runtime.createActorGroup(left);

            assertEquals(Set.of(a.id(), c.id()), left.advisedGroupIds());
            assertEquals(Set.of(b.id()), right.advisedGroupIds());
            assertEquals(Set.of(), runtime.rootSupervisor().advisedGroupIds());
            assertEquals(left.id(), a.supervisorId());
            assertEquals(right.id(), b.supervisorId());

            a.close();
            assertEquals(Set.of(c.id()), left.advisedGroupIds());
            assertEquals(left.id(), a.supervisorId());
            b.close();
            c.close();
            assertTrue(left.advisedGroupIds().isEmpty());
            assertTrue(right.advisedGroupIds().isEmpty());
        }
    }

    @Test
    void rejectsNullAndForeignSupervisorWithoutPublishingGroup() {
        try (ActorRuntime runtime = new ActorRuntime();
             ActorRuntime other = new ActorRuntime()) {
            ActorRuntime.ActorSupervisor foreign = other.createSupervisor();
            assertThrows(NullPointerException.class,
                    () -> runtime.createActorGroup(null));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.createActorGroup(foreign));
            assertEquals(0, runtime.actorGroupCount());
            assertTrue(runtime.rootSupervisor().advisedGroupIds().isEmpty());
        }
    }

    @Test
    void defaultGroupPreservesIndependentMemberAndManagerAuthority() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            assertNotNull(group.supervisorId());
            assertEquals(0, group.workerCount());
            assertEquals(0, group.memberCount());
            assertEquals(Set.of(group.id()),
                    runtime.rootSupervisor().advisedGroupIds());
        }
    }
}
