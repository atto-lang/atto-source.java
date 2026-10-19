package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** CONTROL host-primitive tests. NOT a guest-source or sandbox proof. */
final class ActorUpgradeAtomicPublicationV1Test {
    private static String sha(char c) { return String.valueOf(c).repeat(64); }

    private static ActorUpgradeEpochGate.Image image(char c) {
        return new ActorUpgradeEpochGate.Image(sha(c), sha('1'), sha('2'), sha('3'));
    }

    private static ActorUpgradeAtomicPublicationV1.StateImage state(byte... bytes) {
        return new ActorUpgradeAtomicPublicationV1.StateImage(sha('1'), bytes);
    }

    @Test
    void oneCommitPublishesImageRouteStateAndWatermarkBeforeDispatch() {
        try (var gate = new ActorUpgradeEpochGate(15, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(3, 16, 48);
             var authority = new ActorUpgradeAtomicPublicationV1(
                     gate, mailbox, state((byte) 1), new Object())) {
            var old = authority.snapshot();
            var worker2 = new Object();
            var next = authority.prepare(image('b'), state((byte) 7), worker2);
            assertEquals(1L, authority.snapshot().routeEpoch());
            assertTrue(gate.frozen());
            assertTrue(mailbox.snapshot().dispatchFrozen());
            var buffered = authority.admit(new byte[] {42});
            assertNull(authority.pollDispatch(),
                    "frozen mailbox must never send buffered bytes to old worker");
            assertTrue(authority.publish(next));
            assertEquals(2L, authority.snapshot().routeEpoch());
            assertEquals(2L, gate.epoch());
            assertEquals(2L, mailbox.snapshot().routeEpoch());
            assertEquals(image('b'), authority.snapshot().image());
            assertSame(worker2, authority.snapshot().physical());
            assertEquals(7, authority.snapshot().state().bytes()[0]);
            assertEquals(0L, authority.snapshot().dispatchWatermark());
            assertEquals(old.actorIdentity(), authority.snapshot().actorIdentity());
            var delivered = authority.pollDispatch();
            assertSame(worker2, delivered.route().physical());
            assertEquals(2L, delivered.route().routeEpoch());
            assertEquals(buffered.messageSequence(),
                    delivered.envelope().messageSequence());
            authority.complete(delivered);
            assertEquals(0, mailbox.snapshot().retainedMessages());
            next.close(); // cannot undo an already committed publication
            assertEquals(2L, authority.snapshot().routeEpoch());
        }
    }

    @Test
    void oldDeliveryAndSuspendedTaskBothBlockUntilPhysicalDrain() {
        try (var gate = new ActorUpgradeEpochGate(16, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(3, 16, 48);
             var authority = new ActorUpgradeAtomicPublicationV1(
                     gate, mailbox, state((byte) 1), new Object())) {
            var admitted = authority.admit(new byte[] {11});
            var oldDelivery = authority.pollDispatch();
            var task = gate.pin(ActorUpgradeEpochGate.LeaseKind.SUSPENDED_TASK);
            var next = authority.prepare(image('b'), state((byte) 2), new Object());
            assertFalse(authority.publish(next));
            assertThrows(IllegalStateException.class, gate::pin);
            assertNull(authority.pollDispatch());
            authority.complete(oldDelivery);
            assertEquals(admitted.messageSequence(),
                    mailbox.snapshot().completionWatermark());
            assertFalse(authority.publish(next),
                    "completion alone does not retire suspended Task code");
            task.close();
            assertTrue(authority.publish(next));
            assertEquals(1L, authority.snapshot().dispatchWatermark());
            assertEquals(2L, authority.snapshot().routeEpoch());
        }
    }

    @Test
    void rollbackPreservesOldCodePhysicalStateAndBufferedFifo() {
        try (var gate = new ActorUpgradeEpochGate(17, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 16, 64)) {
            var oldWorker = new Object();
            try (var authority = new ActorUpgradeAtomicPublicationV1(
                    gate, mailbox, state((byte) 3), oldWorker)) {
                var staged = authority.prepare(image('b'), state((byte) 4),
                        new Object());
                authority.admit(new byte[] {1});
                authority.admit(new byte[] {2});
                staged.close();
                staged.close();
                assertEquals(1L, gate.epoch());
                assertEquals(1L, mailbox.snapshot().routeEpoch());
                assertEquals(image('a'), authority.snapshot().image());
                assertSame(oldWorker, authority.snapshot().physical());
                assertEquals(3, authority.snapshot().state().bytes()[0]);
                var one = authority.pollDispatch();
                var two = authority.pollDispatch();
                assertEquals(1L, one.envelope().messageSequence());
                assertEquals(2L, two.envelope().messageSequence());
                assertSame(oldWorker, one.route().physical());
                authority.complete(two);
                authority.complete(one);
                assertEquals(0, mailbox.snapshot().retainedMessages());
            }
        }
    }

    @Test
    void rejectsSchemaMismatchReusedPhysicalAndProtectedPlacement() {
        try (var gate = new ActorUpgradeEpochGate(18, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32)) {
            var physical = new Object();
            try (var authority = new ActorUpgradeAtomicPublicationV1(
                    gate, mailbox, state((byte) 1), physical)) {
                assertThrows(IllegalArgumentException.class, () ->
                        authority.prepare(image('b'), state((byte) 2), physical));
                assertThrows(SecurityException.class, () ->
                        authority.prepare(image('b'),
                                new ActorUpgradeAtomicPublicationV1.StateImage(
                                        sha('4'), new byte[] {1}), new Object()));
                assertFalse(gate.frozen());
            }
        }
        var protectedPlacement = new ActorUpgradeEpochGate.Placement(
                ActorUpgradeEpochGate.ActorKind.UNTRUSTED,
                ActorUpgradeEpochGate.ProtectionDomain.SANDBOX_PROCESS, sha('9'));
        try (var gate = new ActorUpgradeEpochGate(
                     19, protectedPlacement, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32)) {
            assertThrows(SecurityException.class, () ->
                    new ActorUpgradeAtomicPublicationV1(
                            gate, mailbox, state((byte) 1), new Object()));
        }
    }

    @Test
    void mutableCheckpointBuffersCannotChangeCommittedState() {
        byte[] mutable = new byte[] {5, 6};
        var frozen = state(mutable);
        String digest = frozen.payloadSha256();
        mutable[0] = 99;
        frozen.bytes()[1] = 99;
        assertArrayEquals(new byte[] {5, 6}, frozen.bytes());
        assertEquals(digest, frozen.payloadSha256());
        assertThrows(IllegalArgumentException.class,
                () -> state(new byte[1024 * 1024 + 1]));
    }

    @Test
    void foreignAndStaleTicketsCannotCommitAnotherAuthority() {
        try (var gate = new ActorUpgradeEpochGate(20, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32);
             var authority = new ActorUpgradeAtomicPublicationV1(
                     gate, mailbox, state((byte) 1), new Object());
             var otherGate = new ActorUpgradeEpochGate(21, image('a'));
             var otherMail = new LogicalActorProxyMailboxV1(2, 16, 32);
             var other = new ActorUpgradeAtomicPublicationV1(
                     otherGate, otherMail, state((byte) 1), new Object())) {
            var one = authority.prepare(image('b'), state((byte) 2), new Object());
            assertThrows(IllegalStateException.class, () -> other.publish(one));
            assertTrue(authority.publish(one));
            assertThrows(IllegalStateException.class, () -> authority.publish(one));
            assertEquals(1L, other.snapshot().routeEpoch());
        }
    }

    @Test
    void lostGenerationAuthorityQuarantinesDispatchInsteadOfReopeningOldMail() {
        try (var gate = new ActorUpgradeEpochGate(22, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32)) {
            var authority = new ActorUpgradeAtomicPublicationV1(
                    gate, mailbox, state((byte) 1), new Object());
            var staged = authority.prepare(image('b'), state((byte) 2), new Object());
            gate.close();
            assertThrows(IllegalStateException.class, staged::close);
            assertTrue(mailbox.snapshot().dispatchFrozen());
            assertEquals(1L, mailbox.snapshot().routeEpoch());
            assertThrows(IllegalStateException.class, authority::pollDispatch);
            authority.close();
        }
    }

    @Test
    void independentlyChangedGateEpochFailsClosedBeforeAnyDelivery() {
        try (var gate = new ActorUpgradeEpochGate(24, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32);
             var authority = new ActorUpgradeAtomicPublicationV1(
                     gate, mailbox, state((byte) 1), new Object())) {
            authority.admit(new byte[] {1});
            var rogue = gate.prepare(image('c'));
            assertTrue(gate.publish(rogue)); // legacy unsafe independent operation
            assertThrows(IllegalStateException.class, authority::pollDispatch);
            assertThrows(IllegalStateException.class, authority::snapshot);
            assertEquals(1L, mailbox.snapshot().routeEpoch(),
                    "legacy code publication must not fake mailbox cutover");
        }
    }

    @Test
    void independentlyChangedMailboxEpochFailsClosedBeforeAnyDelivery() {
        try (var gate = new ActorUpgradeEpochGate(25, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32);
             var authority = new ActorUpgradeAtomicPublicationV1(
                     gate, mailbox, state((byte) 1), new Object())) {
            authority.admit(new byte[] {1});
            var rogue = mailbox.freezeDispatchForUpgrade();
            assertTrue(rogue.drained());
            rogue.commit(); // legacy unsafe independent operation
            assertThrows(IllegalStateException.class, authority::pollDispatch);
            assertThrows(IllegalStateException.class, authority::snapshot);
            assertEquals(1L, gate.epoch(),
                    "legacy mailbox publication must not fake code generation");
        }
    }

    @Test
    void concurrentDispatchAttemptCannotReadPartialPublishedRecord()
            throws Exception {
        try (var gate = new ActorUpgradeEpochGate(23, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32);
             var authority = new ActorUpgradeAtomicPublicationV1(
                     gate, mailbox, state((byte) 1), new Object())) {
            var nextPhysical = new Object();
            var staged = authority.prepare(image('b'), state((byte) 2),
                    nextPhysical);
            authority.admit(new byte[] {8});
            CountDownLatch begin = new CountDownLatch(1);
            AtomicReference<ActorUpgradeAtomicPublicationV1.Delivery> delivered =
                    new AtomicReference<>();
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread dispatch = Thread.ofPlatform().start(() -> {
                try {
                    begin.await();
                    // If scheduled before commit, frozen dispatch returns null.
                    // Otherwise it sees the entire new immutable route record.
                    delivered.set(authority.pollDispatch());
                } catch (Throwable t) { failed.set(t); }
            });
            begin.countDown();
            assertTrue(authority.publish(staged));
            dispatch.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(dispatch.isAlive());
            assertNull(failed.get());
            var first = delivered.get();
            if (first == null) first = authority.pollDispatch();
            assertNotNull(first);
            assertSame(nextPhysical, first.route().physical());
            assertEquals(2L, first.route().routeEpoch());
            assertEquals(image('b'), first.route().image());
            assertEquals(2, first.route().state().bytes()[0]);
            authority.complete(first);
        }
    }
}
