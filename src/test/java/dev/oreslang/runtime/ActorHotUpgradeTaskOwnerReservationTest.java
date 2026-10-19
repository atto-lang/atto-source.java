package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The canonical OresTaskScope must reserve ownership before executable
 * scheduler admission. Host-only reflection injects a HashSet OOME after the
 * router generation pin is acquired; TaskScope must release that unused pin.
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
            var next = manager.stageActorGeneration("owner-oome-v2.ores",
                    SOURCE.replace("self.count = self.count + mail.value;",
                            "self.count = self.count + mail.value + 1;"));
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 4, 8, 32)) {
                var ownerField = LogicalSourceActorProxyBridgeV1.class
                        .getDeclaredField("taskOwner");
                ownerField.setAccessible(true);
                var owner = (ActorHotUpgradeTaskOwnerV1) ownerField.get(bridge);
                OresTaskScope canonical = owner.canonicalScopeForTesting();
                var tasksField = OresTaskScope.class.getDeclaredField("tasks");
                tasksField.setAccessible(true);
                Object original = tasksField.get(canonical);

                // Fault the canonical scope's ownership reservation. This must
                // happen before router generation pinning/scheduler admission
                // can become executable work.
                tasksField.set(canonical, new HashSet<Object>() {
                    @Override public boolean add(Object slot) {
                        throw new OutOfMemoryError(
                                "injected canonical TaskScope slot allocation failure");
                    }
                });
                try {
                    assertThrows(OutOfMemoryError.class,
                            () -> bridge.startHostPinnedTaskForUpgrade(
                                    scheduler,
                                    resume -> OresScheduler.done(1)));
                } finally {
                    tasksField.set(canonical, original);
                }

                // The route briefly acquired a generation pin, but canonical
                // pre-admission failure released it exactly once. The real
                // SHARED source generation can still change immediately.
                assertTrue(bridge.upgradeTo(next, WAIT),
                        "pre-admission OOME cannot strand an actor generation pin");
                assertFalse(bridge.failSealed());
                assertEquals(2L, bridge.publication().mailbox().routeEpoch());
            }
        }
    }
}
