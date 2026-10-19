package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A structured actor Task must reserve ownership BEFORE admission acquires
 * an old-code generation pin. Host-only reflection injects a HashSet OOME.
 */
@Timeout(45)
final class ActorHotUpgradeTaskOwnerReservationTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    private static final String SOURCE = """
            define actor Counter as
              let int count;
              on_start(): void { self.count = 1; return; }
              receive(ActorMail<int> mail): void {
                self.count = self.count + mail.value;
                self.send(self.count);
                return;
              }
            end
            pub routine main(): void { return; }
            """;

    @Test
    void failedOwnerReservationCannotAdmitOrPinPhysicalTask() throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit());
             var scheduler = new OresScheduler(1)) {
            var first = manager.stageActorGeneration("owner-oome-v1.ores", SOURCE);
            var next = manager.stageActorGeneration("owner-oome-v2.ores", SOURCE);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 4, 8, 32)) {
                var ownerField = LogicalSourceActorProxyBridgeV1.class
                        .getDeclaredField("taskOwner");
                ownerField.setAccessible(true);
                Object owner = ownerField.get(bridge);
                var activeField = owner.getClass().getDeclaredField("active");
                activeField.setAccessible(true);
                Object original = activeField.get(owner);

                // This seam replaces an otherwise private host collection
                // strictly within the test; guest actors cannot reach it.
                activeField.set(owner, new HashSet<Object>() {
                    @Override public boolean add(Object slot) {
                        throw new OutOfMemoryError(
                                "injected owned-slot allocation failure");
                    }
                });
                try {
                    assertThrows(OutOfMemoryError.class,
                            () -> bridge.startHostPinnedTaskForUpgrade(
                                    scheduler,
                                    resume -> OresScheduler.done(1)));
                } finally {
                    activeField.set(owner, original);
                }

                // The actor was never pinned by the rejected Task. Its real
                // SHARED source generation can still change immediately.
                assertTrue(bridge.upgradeTo(next, WAIT),
                        "pre-admission OOME cannot strand an actor generation pin");
                assertFalse(bridge.failSealed());
                assertEquals(2L, bridge.publication().mailbox().routeEpoch());
            }
        }
    }
}
