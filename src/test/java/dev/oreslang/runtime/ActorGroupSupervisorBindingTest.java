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

    @Test
    void supervisorTreeContainsChildSupervisorsAndAdvisedGroups() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorSupervisor grandaddy = runtime.rootSupervisor();
            ActorRuntime.ActorSupervisor daddy1 = runtime.createChildSupervisor(grandaddy);
            ActorRuntime.ActorSupervisor daddy2 = runtime.createChildSupervisor(grandaddy);
            ActorRuntime.ActorSupervisor subSupervisor = runtime.createChildSupervisor(daddy1);
            ActorRuntime.ActorGroup one = runtime.createActorGroup(daddy1);
            ActorRuntime.ActorGroup two = runtime.createActorGroup(subSupervisor);
            ActorRuntime.ActorGroup three = runtime.createActorGroup(daddy2);

            assertEquals(Set.of(daddy1.id(), daddy2.id()), grandaddy.childSupervisorIds());
            assertEquals(Set.of(subSupervisor.id()), daddy1.childSupervisorIds());
            assertEquals(java.util.Optional.of(daddy1.id()), subSupervisor.parentId());
            assertEquals(java.util.Optional.of(grandaddy.id()), daddy2.parentId());
            assertTrue(grandaddy.parentId().isEmpty());

            assertEquals(Set.of(one.id()), daddy1.advisedGroupIds());
            assertEquals(Set.of(one.id(), two.id()), daddy1.subtreeGroupIds());
            assertEquals(Set.of(one.id(), two.id(), three.id()),
                    grandaddy.subtreeGroupIds());
            assertEquals(Set.of(), grandaddy.advisedGroupIds(),
                    "transitive scope must not masquerade as direct authority");

            one.close();
            assertEquals(Set.of(two.id()), daddy1.subtreeGroupIds());
            two.close();
            three.close();
            assertTrue(grandaddy.subtreeGroupIds().isEmpty());
            assertEquals(Set.of(daddy1.id(), daddy2.id()), grandaddy.childSupervisorIds(),
                    "group closure must not silently retire supervisor identity");
        }
    }

    @Test
    void hotUpgradeDoesNotReparentOrCloseSupervisedActorGroups() {
        java.util.concurrent.atomic.AtomicInteger reclaimed = new java.util.concurrent.atomic.AtomicInteger();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.createSupervisor();
            var daddy = runtime.createChildSupervisor(parent);
            var sibling = runtime.createChildSupervisor(parent);
            var group = runtime.createActorGroup(daddy);
            var code = new SupervisorCodeSlot<>("ABI", "Daddy-v1", reclaimed::incrementAndGet, 2);
            daddy.installPolicyCode(code);
            assertEquals(1L, daddy.policyRevision().orElseThrow().revision());
            assertThrows(IllegalStateException.class, () -> sibling.installPolicyCode(code),
                    "one code image slot cannot be installed in two supervisors");
            var old = code.acquire();
            code.activate(1, "ABI", "Daddy-v2", reclaimed::incrementAndGet);
            assertEquals("Daddy-v1", old.code());
            assertEquals(2L, daddy.policyRevision().orElseThrow().revision());
            assertEquals(daddy.id(), group.supervisorId());
            assertEquals(Set.of(group.id()), daddy.advisedGroupIds());
            assertEquals(java.util.Optional.of(parent.id()), daddy.parentId());
            assertEquals(0, reclaimed.get());
            old.close();
            assertEquals(1, reclaimed.get());
            group.close();
        }
        assertEquals(2, reclaimed.get(), "runtime close must retire final code revision");
    }

    @Test
    void foreignAndNullSupervisorParentsAreRejected() {
        try (ActorRuntime runtime = new ActorRuntime();
             ActorRuntime other = new ActorRuntime()) {
            ActorRuntime.ActorSupervisor foreign = other.createSupervisor();
            assertThrows(NullPointerException.class,
                    () -> runtime.createChildSupervisor(null));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.createChildSupervisor(foreign));
            assertEquals(Set.of(), runtime.rootSupervisor().childSupervisorIds());
        }
    }

    @Test
    void recursivelyNestedSupervisorsHaveBoundedDepthAndFailBeforePublication() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorSupervisor root = runtime.rootSupervisor();
            ActorRuntime.ActorSupervisor parent = root;

            // The root counts as depth zero. All 64 nested supervisor levels
            // must retain exact direct-parent linkage and root ancestry.
            for (int depth = 1; depth <= 64; depth++) {
                ActorRuntime.ActorSupervisor created = runtime.createChildSupervisor(parent);
                assertEquals(java.util.Optional.of(parent.id()), created.parentId());
                assertEquals(Set.of(created.id()), parent.childSupervisorIds());
                parent = created;
            }
            ActorRuntime.ActorSupervisor leaf = parent;
            assertTrue(leaf.childSupervisorIds().isEmpty());
            IllegalStateException denied = assertThrows(IllegalStateException.class,
                    () -> runtime.createChildSupervisor(leaf));
            assertTrue(denied.getMessage().contains("hierarchy depth limit"),
                    denied::getMessage);
            assertTrue(leaf.childSupervisorIds().isEmpty(),
                    "depth denial must not publish an orphaned supervisor");

            // A denied deeper child cannot damage the valid existing tree or
            // transfer the leaf's direct ActorGroup ownership to an ancestor.
            ActorRuntime.ActorGroup group = runtime.createActorGroup(leaf);
            assertEquals(Set.of(group.id()), leaf.advisedGroupIds());
            assertEquals(Set.of(group.id()), root.subtreeGroupIds());
            assertTrue(root.advisedGroupIds().isEmpty());
            group.close();
            assertTrue(root.subtreeGroupIds().isEmpty());
        }
    }
}
