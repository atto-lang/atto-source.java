package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java runtime-side pg-style membership snapshot invariants. ActorGroup
 * membership is NOT distributed pg, guest source lowering or lifecycle authority.
 */
@Timeout(15)
final class ActorGroupPgSnapshotParityTest {
    @Test
    void snapshotsCannotAcquireLateJoinersOrMutateLiveMembership() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup room = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = room.joinCapability();
            ActorRuntime.ActorRef<Integer> a = runtime.spawnSharedTrusted(
                    context -> (message, actorContext) -> {});
            ActorRuntime.ActorRef<Integer> b = runtime.spawnSharedTrusted(
                    context -> (message, actorContext) -> {});
            room.join(a, join);
            Set<ActorRuntime.ActorId> before = room.memberIds();
            assertEquals(Set.of(a.id()), before);
            room.join(b, join);
            assertFalse(before.contains(b.id()), "late join retroactively changed snapshot");
            assertEquals(Set.of(a.id(), b.id()), room.memberIds());
            assertThrows(UnsupportedOperationException.class, () -> before.add(b.id()),
                    "membership snapshot must not permit mutation of the group");
            ActorRuntime.ActorGroupJoinCapability rotated = room.rotateJoinCapability();
            assertThrows(SecurityException.class, () -> room.join(a, join),
                    "old join permit must remain revoked even for an existing member");
            room.join(a, rotated); // duplicate membership does not charge quota
            assertEquals(2, room.memberCount());
            room.close();
            assertEquals(0, runtime.actorGroupCount());
            assertTrue(a.isAlive(), "membership-only group closure cannot stop actor A");
            assertTrue(b.isAlive(), "membership-only group closure cannot stop actor B");
            a.stop();
            b.stop();
            assertTrue(a.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
