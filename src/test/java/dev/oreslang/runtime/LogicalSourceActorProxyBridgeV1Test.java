package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real actor/runtime integration coverage (not just a model mailbox).
 * These source actors execute on SHARED Java/Graal contexts; this does not
 * prove per-actor protected address spaces or LLVM image loading.
 */
@Timeout(60)
final class LogicalSourceActorProxyBridgeV1Test {
    private static final Duration WAIT = Duration.ofSeconds(5);

    private static final String V1 = """
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

    private static final String V2 = """
            define actor Counter as
              let int count;
              on_start(): void { self.count = 999; return; }
              receive(ActorMail<int> mail): void {
                if mail.value < 0; do
                  self.count = self.count - mail.value;
                else
                  self.count = self.count + mail.value;
                fi
                self.send(self.count);
                return;
              }
            end
            pub routine main(): void { return; }
            """;

    private static long next(LogicalSourceActorProxyBridgeV1 bridge)
            throws Exception {
        var step = bridge.nextOutput(WAIT);
        assertFalse(step.done(), "actor output must not terminate");
        return assertInstanceOf(Long.class, step.value().value());
    }

    @Test
    void realSourceActorDrainsProxyDeliveryBeforeRefundingCredits()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var initial = manager.stageActorGeneration("bridge-v1.ores", V1);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    initial, "Counter", WAIT, 2, 8, 16)) {
                var one = bridge.admitLong(41L);
                var two = bridge.admitLong(1L);
                assertEquals(1L, one.messageSequence());
                assertEquals(2L, two.messageSequence());
                assertEquals(2, bridge.snapshot().retainedMessages());
                assertThrows(IllegalStateException.class,
                        () -> bridge.admitLong(3L),
                        "queued message credits are bounded before execution");

                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
                assertEquals(1, bridge.snapshot().retainedMessages());
                assertEquals(1L, bridge.snapshot().completionWatermark());

                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(43L, next(bridge));
                assertEquals(0, bridge.snapshot().retainedMessages());
                assertEquals(2L, bridge.snapshot().completionWatermark());
                assertFalse(bridge.dispatchOne(WAIT));
                assertFalse(bridge.failSealed());
            }
        }
    }

    @Test
    void queuedMessagesCrossRealSourceUpgradeWithStableStateAndRouteIdentity()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var initial = manager.stageActorGeneration("bridge-v1.ores", V1);
            var candidate = manager.stageActorGeneration("bridge-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    initial, "Counter", WAIT, 4, 8, 32)) {
                var before = bridge.address();
                bridge.admitLong(41L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));

                // Retained immutable wire bytes are admitted but not yet
                // sent physically; route switching must preserve their FIFO.
                bridge.admitLong(-1L);
                bridge.admitLong(-2L);
                assertEquals(2, bridge.snapshot().queuedMessages());
                assertTrue(bridge.upgradeTo(candidate, WAIT));

                var after = bridge.address();
                assertEquals(before.actorId(), after.actorId());
                assertEquals(before.incarnation(), after.incarnation());
                assertEquals(before.routeEpoch() + 1L, after.routeEpoch());
                assertEquals(after.routeEpoch(), bridge.snapshot().routeEpoch());
                assertEquals(2, bridge.snapshot().queuedMessages());

                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(43L, next(bridge));
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(45L, next(bridge));
                assertEquals(0, bridge.snapshot().retainedMessages());
                assertEquals(3L, bridge.snapshot().completionWatermark());
                assertFalse(bridge.failSealed());
            }
        }
    }

    @Test
    void queuedBytesSurviveAQuiescentUpgrade()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var initial = manager.stageActorGeneration("bridge-v1.ores", V1);
            var candidate = manager.stageActorGeneration("bridge-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    initial, "Counter", WAIT, 2, 8, 16)) {
                // Queued-but-not-dispatched bytes do not block a code switch;
                // they will be delivered to the new authoritative generation.
                bridge.admitLong(41L);
                assertTrue(bridge.upgradeTo(candidate, WAIT));
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
                assertEquals(1L, bridge.snapshot().completionWatermark());
            }
        }
    }

    @Test
    void closingCannotSilentlyDropAcceptedQueuedActorMail() throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("queued-close.ores", V1);
            var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 2, 8, 16);
            try {
                bridge.admitLong(41L);
                var before = bridge.snapshot();
                assertThrows(IllegalStateException.class, bridge::close,
                        "proxy admission is not permission to discard accepted mail");
                assertEquals(before, bridge.snapshot());
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
                assertDoesNotThrow(bridge::close);
                assertThrows(IllegalStateException.class,
                        () -> bridge.admitLong(1L));
            } finally {
                // If a test assertion fails with queued credits, retain the
                // live generation rather than silently treating mail as done.
                if (bridge.snapshot().retainedMessages() == 0) {
                    bridge.close();
                }
            }
        }
    }

    @Test
    void closedBridgeForbidsLateMailboxAdmission()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("closed.ores", V1);
            var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 1, 8, 8);
            bridge.close();
            assertThrows(IllegalStateException.class,
                    () -> bridge.admitLong(1L));
            assertThrows(IllegalStateException.class,
                    () -> bridge.dispatchOne(WAIT));
            assertEquals(0, bridge.snapshot().retainedMessages());
        }
    }
}
