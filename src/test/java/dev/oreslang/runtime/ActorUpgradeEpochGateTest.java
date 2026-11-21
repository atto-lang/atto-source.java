package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void failedPinAccountingIsRetryableWithoutPrematureRelease() throws Exception {
        try (var gate = new ActorUpgradeEpochGate(42, image('a'))) {
            var turn = gate.pin();
            var candidate = gate.prepare(image('b'));
            var count = ActorUpgradeEpochGate.class.getDeclaredField("pins");
            count.setAccessible(true);
            try {
                count.setInt(gate, 0); // deterministic corruption/fault injection
                assertThrows(IllegalStateException.class, turn::close);
                assertTrue(gate.permits(turn), "failed retirement must retain its lease");
            } finally {
                count.setInt(gate, 1);
            }
            assertFalse(gate.publish(candidate));
            turn.close();
            turn.close();
            assertEquals(0, gate.livePins());
            assertFalse(gate.permits(turn));
            assertTrue(gate.publish(candidate));
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
    void sequentialUpgradeGenerationsRetainActorIdentityAndInvalidateOldLeases() {
        try (var gate = new ActorUpgradeEpochGate(92, image('a'))) {
            long expectedEpoch = 1;
            for (char nextCode : new char[] {'b', 'c', 'd', 'e', 'f'}) {
                var lease = gate.pin();
                var proposed = image(nextCode);
                var candidate = gate.prepare(proposed);
                assertTrue(gate.permits(lease),
                        "existing pinned turn may drain while admission is frozen");
                assertFalse(gate.publish(candidate));
                lease.close();
                assertTrue(gate.publish(candidate));
                assertFalse(gate.permits(lease), "retired lease must not authorize new generation");
                assertEquals(++expectedEpoch, gate.epoch());
                assertEquals(proposed, gate.image());
                assertEquals(92, gate.actorIdentity());
            }
        }
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
            worker.setDaemon(true);
            worker.start();
            try {
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS),
                        "lease-release worker must start");
                assertFalse(gate.publish(next),
                        "publication must not race an outstanding lease");
            } finally {
                finish.countDown();
                worker.join(5000);
            }
            assertFalse(worker.isAlive(), "lease-release worker did not terminate");
            assertNull(workerFailure.get());
            assertTrue(gate.publish(next));
            assertEquals(2, gate.epoch());
        }
    }

    @Test
    void sourceTaskPhysicalRetirementReleasesUpgradeEpochPinBeforePublication()
            throws Exception {
        try (var gate = new ActorUpgradeEpochGate(77, image('a'));
             var scheduler = new OresScheduler(1)) {
            var sourceEpoch = gate.pin();
            var candidate = gate.prepare(image('b'));
            CountDownLatch retirementHookEntered = new CountDownLatch(1);
            AtomicReference<Runnable> releaseRetirement = new AtomicReference<>();

            OresFuture<Integer> result = scheduler.start(
                    new OresScheduler.Task<Integer>() {
                        @Override
                        public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resume) {
                            return OresScheduler.done(7);
                        }

                        @Override
                        public void afterCarrierQuiescent(Runnable retire) {
                            assertTrue(releaseRetirement.compareAndSet(null, retire));
                            retirementHookEntered.countDown();
                        }
                    },
                    sourceEpoch::close);

            assertTrue(retirementHookEntered.await(5, TimeUnit.SECONDS));
            assertTrue(gate.permits(sourceEpoch));
            assertFalse(
                    gate.publish(candidate),
                    "new code epoch published while old source Task still pinned");
            assertFalse(
                    result.isDone(),
                    "Task result must not publish before the epoch retirement callback");

            Runnable retire = releaseRetirement.get();
            assertNotNull(retire);
            retire.run();

            assertEquals(7, result.get(5, TimeUnit.SECONDS));
            assertFalse(gate.permits(sourceEpoch));
            assertEquals(0, gate.livePins());
            assertTrue(
                    gate.publish(candidate),
                    "new code epoch should publish after physical source Task retirement");
            assertEquals(2, gate.epoch());
            assertEquals(image('b'), gate.image());
        }
    }


}
