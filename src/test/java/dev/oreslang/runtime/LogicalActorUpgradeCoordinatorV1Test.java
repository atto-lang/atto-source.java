package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class LogicalActorUpgradeCoordinatorV1Test {
    private static String hash(char c) { return String.valueOf(c).repeat(64); }
    private static ActorUpgradeEpochGate.Image image(char code) {
        return new ActorUpgradeEpochGate.Image(hash(code),hash('1'),hash('2'),hash('3'));
    }
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void freezesNewDispatchUntilOldRouteMessagesAndSuspendedWorkDrain() {
        try (var gate = new ActorUpgradeEpochGate(7,image('a'));
             var proxy = new LogicalActorProxyMailboxV1(8,64,512);
             var coordinator = new LogicalActorUpgradeCoordinatorV1(gate,proxy)) {
            var first = proxy.admit(bytes("old"));
            assertEquals(first.messageSequence(),proxy.pollDispatch().messageSequence());
            var taskPin = gate.pin(); // a suspended continuation still pins old image
            var transaction = coordinator.prepare(image('b'));
            assertNull(proxy.pollDispatch());
            var queued = proxy.admit(bytes("new"));
            assertEquals(2L,queued.messageSequence());
            assertFalse(transaction.commit());
            proxy.complete(first.messageSequence());
            assertFalse(transaction.commit(), "Task must physically exit before upgrade");
            taskPin.close();
            assertTrue(transaction.drained());
            assertTrue(transaction.commit());
            assertEquals(2L,gate.epoch());
            assertEquals(2L,proxy.snapshot().routeEpoch());
            assertEquals(2L,proxy.pollDispatch().messageSequence());
            proxy.complete(2L);
        }
    }

    @Test
    void rollbackRestoresOldRouteWithoutDroppingQueuedMessages() {
        try (var gate = new ActorUpgradeEpochGate(8,image('a'));
             var proxy = new LogicalActorProxyMailboxV1(4,64,256);
             var coordinator = new LogicalActorUpgradeCoordinatorV1(gate,proxy)) {
            var tx = coordinator.prepare(image('b'));
            proxy.admit(bytes("held"));
            assertNull(proxy.pollDispatch());
            tx.rollback();
            assertEquals(image('a'),gate.image());
            assertEquals(1L,gate.epoch());
            assertEquals(1L,proxy.snapshot().routeEpoch());
            assertEquals(1L,proxy.pollDispatch().messageSequence());
            proxy.complete(1L);
            assertThrows(IllegalStateException.class,tx::commit);
        }
    }

    @Test
    void incompatibleCodeDoesNotLeaveFrozenMailbox() {
        try (var gate = new ActorUpgradeEpochGate(9,image('a'));
             var proxy = new LogicalActorProxyMailboxV1(4,64,256);
             var coordinator = new LogicalActorUpgradeCoordinatorV1(gate,proxy)) {
            var incompatible = new ActorUpgradeEpochGate.Image(
                    hash('b'),hash('9'),hash('2'),hash('3'));
            assertThrows(SecurityException.class,()->coordinator.prepare(incompatible));
            assertFalse(proxy.snapshot().dispatchFrozen());
            assertFalse(coordinator.quarantined());
            assertEquals(1L,gate.epoch());
            assertNotNull(proxy.pollDispatch()==null);
        }
    }

    @Test
    void staleForeignOrMismatchedEpochRejected() {
        try (var gate = new ActorUpgradeEpochGate(12,image('a'));
             var proxy = new LogicalActorProxyMailboxV1(4,64,256)) {
            var tx = proxy.freezeDispatchForUpgrade();
            tx.commit();
            assertThrows(IllegalArgumentException.class,
                    () -> new LogicalActorUpgradeCoordinatorV1(gate,proxy));
        }
    }
}
