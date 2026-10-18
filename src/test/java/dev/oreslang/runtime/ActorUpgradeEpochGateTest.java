package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Host-only transition proof, not an Oreslang guest actor hot-swap test. */
final class ActorUpgradeEpochGateTest {
    private static String hex(char digit) { return String.valueOf(digit).repeat(64); }

    private static ActorUpgradeEpochGate.Image image(char code) {
        return new ActorUpgradeEpochGate.Image(hex(code), hex('1'), hex('2'), hex('3'));
    }

    @Test
    void hotSwitchWaitsForOutstandingTurnAndInvalidatesOldTicket() {
        try (var gate = new ActorUpgradeEpochGate(42, image('a'))) {
            var turn = gate.pin();
            assertTrue(gate.permits(turn));
            var replacement = gate.prepare(image('b'));
            assertTrue(gate.frozen());
            assertThrows(IllegalStateException.class, gate::pin);
            assertFalse(gate.publish(replacement));
            assertEquals(1, gate.epoch());
            assertEquals(image('a'), gate.image());
            turn.close();
            turn.close(); // idempotent
            assertEquals(0, gate.livePins());
            assertFalse(gate.permits(turn));
            assertTrue(gate.publish(replacement));
            assertEquals(2, gate.epoch());
            assertEquals(image('b'), gate.image());
            assertThrows(IllegalStateException.class, () -> gate.publish(replacement));
        }
    }

    @Test
    void incompatibleSchemaWireOrCapabilitiesFailClosed() {
        try (var gate = new ActorUpgradeEpochGate(1, image('a'))) {
            for (int index = 0; index < 3; index++) {
                var next = new ActorUpgradeEpochGate.Image(hex('b'),
                        index == 0 ? hex('4') : hex('1'),
                        index == 1 ? hex('4') : hex('2'),
                        index == 2 ? hex('4') : hex('3'));
                assertThrows(SecurityException.class, () -> gate.prepare(next));
                assertFalse(gate.frozen());
            }
            assertThrows(IllegalArgumentException.class, () -> gate.prepare(image('a')));
            assertThrows(IllegalArgumentException.class,
                    () -> new ActorUpgradeEpochGate.Image("a", hex('1'), hex('2'), hex('3')));
        }
    }

    @Test
    void cannotUseCandidateAcrossActorsOrAfterRollback() {
        try (var alpha = new ActorUpgradeEpochGate(1, image('a'));
             var beta = new ActorUpgradeEpochGate(2, image('a'))) {
            var ticket = alpha.prepare(image('b'));
            assertThrows(IllegalStateException.class, () -> beta.publish(ticket));
            alpha.abort(ticket);
            assertThrows(IllegalStateException.class, () -> alpha.publish(ticket));
            assertEquals(image('a'), alpha.image());
            assertFalse(alpha.frozen());
            var turn = alpha.pin();
            assertEquals(1, turn.epoch());
            turn.close();
        }
    }

    @Test
    void closingGateRejectsPublicationButWaitsForExistingPins() {
        var gate = new ActorUpgradeEpochGate(9, image('a'));
        var turn = gate.pin();
        var candidate = gate.prepare(image('b'));
        gate.close();
        assertFalse(gate.retired());
        assertThrows(IllegalStateException.class, () -> gate.publish(candidate));
        assertThrows(IllegalStateException.class, gate::pin);
        turn.close();
        assertTrue(gate.retired());
        assertEquals(image('a'), gate.image());
    }

    @Test
    void concurrentLeaseReleaseAndUpgradeCannotPublishEarly() throws Exception {
        try (var gate = new ActorUpgradeEpochGate(17, image('a'))) {
            var turn = gate.pin();
            var next = gate.prepare(image('b'));
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                started.countDown();
                try {
                    finish.await();
                    turn.close();
                } catch (Throwable t) { workerFailure.set(t); }
            });
            worker.start();
            started.await();
            assertFalse(gate.publish(next));
            finish.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertNull(workerFailure.get());
            assertTrue(gate.publish(next));
            assertEquals(2, gate.epoch());
        }
    }
}
