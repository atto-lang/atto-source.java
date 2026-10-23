package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The completed prefix is the only sufficient old-route drain certificate.
 * These are host mailbox tests, not authenticated physical actor receipts.
 */
final class LogicalActorProxyUpgradeWatermarkTest {
    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void sparseLaterCompletionsCannotCertifyAnOlderMissingDispatch() {
        try (var proxy = new LogicalActorProxyMailboxV1(128, 64, 8192)) {
            for (int i = 1; i <= 64; i++) {
                proxy.admit(bytes("old-" + i));
            }
            for (int i = 1; i <= 64; i++) {
                assertEquals(i, proxy.pollDispatch().messageSequence());
            }
            var freeze = proxy.freezeDispatchForUpgrade();
            assertEquals(64, freeze.dispatchWatermark());
            for (int i = 64; i >= 2; i--) {
                proxy.complete(i);
                assertFalse(freeze.drained(),
                        "a completed higher sequence cannot hide missing old sequence 1");
            }
            assertEquals(0, proxy.snapshot().completionWatermark());
            assertEquals(63, proxy.snapshot().sparseCompletedMessages());
            assertThrows(IllegalStateException.class, freeze::commit);
            proxy.admit(bytes("new-route-after-freeze"));
            assertNull(proxy.pollDispatch());
            proxy.complete(1);
            assertEquals(64, proxy.snapshot().completionWatermark());
            assertTrue(freeze.drained());
            freeze.commit();
            assertEquals(2, proxy.snapshot().routeEpoch());
            assertEquals(65, proxy.pollDispatch().messageSequence());
        }
    }

    @Test
    void emptyOldPrefixMayCommitWhileSuccessorAdmissionsRemainQueued() {
        try (var proxy = new LogicalActorProxyMailboxV1(8, 64, 512)) {
            var freeze = proxy.freezeDispatchForUpgrade();
            assertEquals(0, freeze.dispatchWatermark());
            proxy.admit(bytes("successor-only"));
            assertTrue(freeze.drained());
            freeze.commit();
            assertEquals(1, proxy.pollDispatch().messageSequence());
            assertEquals(2, proxy.snapshot().routeEpoch());
        }
    }

    @Test
    void repeatedFreezeAbortCyclesPreserveEpochAndNeverSkipOldReceipts() {
        try (var proxy = new LogicalActorProxyMailboxV1(64, 64, 4096)) {
            List<Long> accepted = new ArrayList<>();
            for (int round = 0; round < 24; round++) {
                var envelope = proxy.admit(bytes("turn-" + round));
                accepted.add(envelope.messageSequence());
                assertEquals(envelope.messageSequence(),
                        proxy.pollDispatch().messageSequence());
                var freeze = proxy.freezeDispatchForUpgrade();
                assertFalse(freeze.drained());
                assertThrows(IllegalStateException.class, freeze::commit);
                if (round % 2 == 0) {
                    freeze.rollback();
                    assertEquals(round + 1L, proxy.snapshot().routeEpoch());
                    // Re-freeze the same physical dispatch before acknowledging.
                    freeze = proxy.freezeDispatchForUpgrade();
                }
                proxy.complete(envelope.messageSequence());
                assertTrue(freeze.drained());
                freeze.commit();
                assertEquals(round + 2L, proxy.snapshot().routeEpoch());
            }
            assertEquals(24, accepted.size());
            assertEquals(0, proxy.snapshot().retainedMessages());
        }
    }

    @Test
    void requeuedProvenUnsentMessageStillBlocksUpgradeUntilRedispatchedAndCompleted() {
        try (var proxy = new LogicalActorProxyMailboxV1(8, 64, 512)) {
            proxy.admit(bytes("physical-refusal"));
            var dispatched = proxy.pollDispatch();
            proxy.requeueProvenUnsent(dispatched.messageSequence());
            assertEquals(0, proxy.snapshot().highestDispatchedSequence());
            var freeze = proxy.freezeDispatchForUpgrade();
            assertTrue(freeze.drained(),
                    "no physical dispatch was admitted before freeze");
            freeze.commit();
            assertEquals(1L, proxy.pollDispatch().messageSequence());
            var secondFreeze = proxy.freezeDispatchForUpgrade();
            assertFalse(secondFreeze.drained());
            proxy.complete(1);
            assertTrue(secondFreeze.drained());
            secondFreeze.commit();
        }
    }
}
