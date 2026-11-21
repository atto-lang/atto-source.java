package dev.oreslang.runtime;

import java.lang.reflect.Field;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A code/route-epoch match alone is not dispatch authority: a previously
 * published actor can retain an unsettled retired predecessor.
 */
@Timeout(60)
final class LogicalSourceActorPublicationQuarantineTest {
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
    void coherentPublicationReportsRetirementFenceWithoutEpochDrift()
            throws Exception {
        try (var manager = new HotReloadManager(
                    IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration(
                    "retirement-publication-witness.ores", SOURCE);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                        first, "Counter", WAIT, 2, Long.BYTES, 2L * Long.BYTES)) {
                // Inject only the supervisor-owned retirement fence. It is a
                // synthetic post-publication state, not an executed upgrade.
                Field routerField = LogicalSourceActorProxyBridgeV1.class
                        .getDeclaredField("router");
                routerField.setAccessible(true);
                var router = (LogicalSourceActorRouterV1) routerField.get(bridge);
                Field fence = LogicalSourceActorRouterV1.class
                        .getDeclaredField("retirementQuarantined");
                fence.setAccessible(true);

                var healthy = bridge.publication();
                assertTrue(healthy.authoritativeForDispatch());
                assertFalse(healthy.retirementQuarantined());
                fence.setBoolean(router, true);
                try {
                    var blocked = bridge.publication();
                    assertTrue(blocked.retirementQuarantined());
                    assertFalse(blocked.authoritativeForDispatch(),
                            "quarantined physical retirement revokes dispatch authority");
                    assertEquals(healthy.address(), blocked.address());
                    assertEquals(healthy.mailbox().routeEpoch(),
                            blocked.mailbox().routeEpoch(),
                            "unchanged epochs must not mask retirement quarantine");
                    assertFalse(bridge.failSealed(),
                            "a recoverable supervisor fence is not bridge poisoning");
                } finally {
                    // Do not leave synthetic state on a genuine physical actor.
                    fence.setBoolean(router, false);
                }
                var recovered = bridge.publication();
                assertFalse(recovered.retirementQuarantined());
                assertTrue(recovered.authoritativeForDispatch());
                bridge.admitLong(41L);
                assertTrue(bridge.dispatchOne(WAIT));
                var output = bridge.nextOutput(WAIT);
                assertFalse(output.done());
                long observed = assertInstanceOf(
                        Long.class, output.value().value());
                assertEquals(42L, observed);
            }
        }
    }
}
