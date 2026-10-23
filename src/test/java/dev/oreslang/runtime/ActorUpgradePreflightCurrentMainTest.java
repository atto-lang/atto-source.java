package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorUpgradePreflightCurrentMainTest {
    private static String sha(char c) {
        return String.valueOf(c).repeat(64);
    }

    private static ActorUpgradeEpochGate.Image image(char code) {
        return new ActorUpgradeEpochGate.Image(
                sha(code), sha('1'), sha('2'), sha('3'));
    }

    @Test
    void oldMessageAndTypedPhysicalLeaseBothBlockReadinessWhileSuccessorAdmissionStaysQueued() {
        try (var gate = new ActorUpgradeEpochGate(42, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(8, 64, 256)) {
            var old = mailbox.admit(new byte[] {1});
            assertEquals(old.messageSequence(), mailbox.pollDispatch().messageSequence());

            var ffi = gate.pin(ActorUpgradeEpochGate.LeaseKind.FFI_CALLBACK);
            try (var preflight = ActorUpgradePreflightV1.prepare(
                    gate, mailbox, image('b'))) {
                var successor = mailbox.admit(new byte[] {2});
                assertNull(mailbox.pollDispatch(), "DATA dispatch must remain frozen");
                assertFalse(preflight.quiescentSnapshot());

                mailbox.complete(old.messageSequence());
                assertFalse(preflight.quiescentSnapshot(),
                        "physical generation lease must still block publication");

                ffi.close();
                assertTrue(preflight.quiescentSnapshot());

                preflight.close();
                assertEquals(1L, mailbox.snapshot().routeEpoch());
                assertEquals(1L, gate.epoch());
                assertEquals(successor.messageSequence(),
                        mailbox.pollDispatch().messageSequence());
            }
        }
    }

    @Test
    void publicationChangesGenerationAndMailboxEpochBeforeDispatchReopens() {
        try (var gate = new ActorUpgradeEpochGate(7, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(8, 64, 256)) {
            var old = mailbox.admit(new byte[] {1});
            mailbox.pollDispatch();

            var preflight = ActorUpgradePreflightV1.prepare(
                    gate, mailbox, image('b'));
            mailbox.complete(old.messageSequence());

            final boolean[] published = {false};
            preflight.commitWithAuthoritativeRoute(() -> published[0] = true);

            assertTrue(published[0]);
            assertEquals(2L, gate.epoch());
            assertEquals(2L, mailbox.snapshot().routeEpoch());
            assertFalse(mailbox.snapshot().dispatchFrozen());
            assertEquals(image('b'), gate.image());
        }
    }
}
