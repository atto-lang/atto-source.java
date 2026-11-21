package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-issued message ABI is part of SHARED proxy upgrade admission.
 * A new actor generation cannot consume immutable mail admitted under an
 * incompatible predecessor's receive signature.
 */
@Timeout(60)
final class SourceActorUpgradeMessageAbiGateTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String INT_ACTOR = """
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
    private static final String STRING_ACTOR = """
            define actor Counter as
              let int count;
              on_start(): void { self.count = 1; return; }
              receive(ActorMail<string> mail): void {
                self.send(self.count);
                return;
              }
            end
            pub routine main(): void { return; }
            """;

    @Test
    void mismatchedCompilerMessageAbiIsRejectedBeforePhysicalTransfer()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var original = manager.stageActorGeneration("abi-upgrade-int.ores", INT_ACTOR);
            var incompatible = manager.stageActorGeneration(
                    "abi-upgrade-string.ores", STRING_ACTOR);
            var oldView = original.linkActorGeneration("Counter");
            var newView = incompatible.linkActorGeneration("Counter");
            assertNotEquals(
                    oldView.executor().actorMessageAbiSha256("Counter"),
                    newView.executor().actorMessageAbiSha256("Counter"),
                    "the fixture must genuinely have a different compiler receive ABI");

            try (var router = LogicalSourceActorRouterV1.spawn(
                         original, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var mailbox = new LogicalActorProxyMailboxV1(
                         3, SourceActorScalarWireV1.BYTE_LENGTH,
                         3L * SourceActorScalarWireV1.BYTE_LENGTH)) {
                router.bindProxyMailboxForSupervisor(mailbox, true);
                var binding = router.scalarWireBindingForProxy(mailbox);
                var oldAddress = router.ref().address();

                var queued = mailbox.admit(
                        SourceActorScalarWireV1.encodeLong(binding, 41L));
                assertEquals(1, mailbox.snapshot().queuedMessages());

                var ticket = mailbox.freezeDispatchForUpgrade();
                try {
                    assertTrue(ticket.drained());
                    assertThrows(SecurityException.class,
                            () -> router.upgradeToForProxy(
                                    incompatible, WAIT, mailbox, ticket),
                            "message ABI drift must be denied before state transfer");
                    assertEquals(oldAddress, router.ref().address());
                    assertEquals(1L, mailbox.snapshot().routeEpoch());
                    assertTrue(mailbox.snapshot().dispatchFrozen(),
                            "failed source ABI check cannot commit or consume freeze");
                    assertEquals(1, mailbox.snapshot().queuedMessages());
                } finally {
                    ticket.rollback();
                }

                assertSame(queued, mailbox.pollDispatch());
                var receipt = router.sendAndDrainForProxy(
                        mailbox, queued, 1L);
                receipt.awaitAndComplete(queued, WAIT);
                var output = router.ref().nextOutput(WAIT);
                assertFalse(output.done());
                assertEquals(42L,
                        assertInstanceOf(Long.class, output.value().value()));
                assertEquals(0, mailbox.snapshot().retainedMessages());

                // Linking a rejected candidate is not permission to retain
                // executable generations or leak a physical actor route pin.
                manager.retire(incompatible.id());
                assertTrue(incompatible.closed());
            }
        }
    }
}
