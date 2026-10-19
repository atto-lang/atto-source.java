package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A coherent compiler/physical/mailbox epoch is not permission to dispatch
 * during an upgrade freeze. ORM2 wire compatibility must remain bound to the
 * exact compiler receive contract on both sides of a freeze.
 */
@Timeout(45)
final class LogicalSourceActorFrozenPublicationTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String SOURCE = """
            define actor TypedSink as
              let string last;
              on_start(): void { self.last = ""; return; }
              receive(ActorMail<string> mail): void {
                self.last = mail.value;
                self.send(self.last);
                return;
              }
            end
            pub routine main(): void { return; }
            """;

    @Test
    void orm2FrozenRouteNeverReportsDispatchAuthorityUntilExplicitRollback()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var initial = manager.stageActorGeneration("orm2-frozen.ores", SOURCE);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnSharedTypedPrimitive(
                    initial, "TypedSink", WAIT, 3, 128, 384)) {
                Field mailboxField = LogicalSourceActorProxyBridgeV1.class
                        .getDeclaredField("mailbox");
                mailboxField.setAccessible(true);
                var mailbox = (LogicalActorProxyMailboxV1) mailboxField.get(bridge);
                var first = bridge.publication();
                assertTrue(first.authoritativeForDispatch());
                assertFalse(first.failSealed());
                assertEquals(1L, first.address().routeEpoch());

                var ticket = mailbox.freezeDispatchForUpgrade();
                LogicalSourceActorProxyBridgeV1.Publication frozen;
                try {
                    frozen = bridge.publication();
                    assertEquals(first.generationId(), frozen.generationId());
                    assertEquals(first.linkedCodeSha256(), frozen.linkedCodeSha256());
                    assertEquals(first.messageAbiSha256(), frozen.messageAbiSha256());
                    assertEquals(first.address().routeEpoch(), frozen.mailbox().routeEpoch());
                    assertTrue(frozen.mailbox().dispatchFrozen());
                    assertFalse(frozen.failSealed(),
                            "a planned physical freeze is not itself a terminal failure");
                    assertFalse(frozen.authoritativeForDispatch(),
                            "coherent image and epoch cannot authorize frozen dispatch");

                    bridge.admitString("during freeze");
                    assertEquals(1, mailbox.snapshot().queuedMessages());
                    assertFalse(bridge.dispatchOne(WAIT),
                            "frozen mailbox cannot physically dispatch queued mail");
                } finally {
                    ticket.rollback();
                }
                assertFalse(frozen.authoritativeForDispatch(),
                        "an immutable prior snapshot must not be retroactively promoted");
                var thawed = bridge.publication();
                assertFalse(thawed.mailbox().dispatchFrozen());
                assertFalse(thawed.failSealed());
                assertTrue(thawed.authoritativeForDispatch());

                assertTrue(bridge.dispatchOne(WAIT));
                var output = bridge.nextOutput(WAIT);
                assertFalse(output.done());
                assertEquals("during freeze", output.value().value());
                assertEquals(0, bridge.snapshot().retainedMessages());
            }
        }
    }
}
