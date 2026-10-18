package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class LogicalActorProxyMailboxV1Test {

    @Test
    void freezeStopsDispatchButKeepsBoundedAdmissionOpenUntilCommit() {
        try (var mailbox = new LogicalActorProxyMailboxV1(
                8, 1024, 4096)) {
            var one = mailbox.admit(bytes("one"));
            var two = mailbox.admit(bytes("two"));
            assertEquals(1L, one.messageSequence());
            assertEquals(2L, two.messageSequence());

            assertEquals(1L, mailbox.pollDispatch().messageSequence());
            assertEquals(2L, mailbox.pollDispatch().messageSequence());

            var freeze = mailbox.freezeDispatchForUpgrade();
            assertEquals(2L, freeze.dispatchWatermark());
            assertEquals(1L, freeze.routeEpoch());
            assertFalse(freeze.drained());

            var three = mailbox.admit(bytes("three"));
            var four = mailbox.admit(bytes("four"));
            assertEquals(3L, three.messageSequence());
            assertEquals(4L, four.messageSequence());
            assertNull(mailbox.pollDispatch(),
                    "dispatch is frozen while admission remains open");

            mailbox.complete(2L);
            assertFalse(freeze.drained(),
                    "message 1 still belongs to the old route prefix");
            mailbox.complete(1L);
            assertTrue(freeze.drained());

            freeze.commit();
            assertEquals(2L, mailbox.snapshot().routeEpoch());
            assertEquals(3L, mailbox.pollDispatch().messageSequence());
            assertEquals(4L, mailbox.pollDispatch().messageSequence());
        }
    }

    @Test
    void rollbackPreservesExactOldRouteAndQueuedFifo() {
        try (var mailbox = new LogicalActorProxyMailboxV1(
                8, 1024, 4096)) {
            mailbox.admit(bytes("one"));
            mailbox.admit(bytes("two"));
            assertEquals(1L, mailbox.pollDispatch().messageSequence());

            var freeze = mailbox.freezeDispatchForUpgrade();
            mailbox.admit(bytes("three"));
            mailbox.complete(1L);
            assertTrue(freeze.drained());
            freeze.rollback();

            assertEquals(1L, mailbox.snapshot().routeEpoch());
            assertEquals(2L, mailbox.pollDispatch().messageSequence());
            assertEquals(3L, mailbox.pollDispatch().messageSequence());
        }
    }

    @Test
    void capacityCoversQueuedAndCheckedOutUntilLogicalCompletion() {
        try (var mailbox = new LogicalActorProxyMailboxV1(
                2, 16, 16)) {
            mailbox.admit(new byte[8]);
            mailbox.admit(new byte[8]);
            var dispatched = mailbox.pollDispatch();
            assertNotNull(dispatched);

            assertThrows(
                    IllegalStateException.class,
                    () -> mailbox.admit(new byte[] {1}),
                    "dispatch alone must not refund a message credit");

            mailbox.complete(dispatched.messageSequence());
            assertDoesNotThrow(() -> mailbox.admit(new byte[] {1}));
            assertEquals(2, mailbox.snapshot().retainedMessages());
        }

        try (var mailbox = new LogicalActorProxyMailboxV1(
                4, 16, 16)) {
            mailbox.admit(new byte[12]);
            assertThrows(
                    IllegalStateException.class,
                    () -> mailbox.admit(new byte[5]));
        }
    }

    @Test
    void outOfOrderCompletionUsesBoundedGapWindow() {
        try (var mailbox = new LogicalActorProxyMailboxV1(
                4, 8, 32)) {
            for (int i = 0; i < 4; i++) mailbox.admit(new byte[] {(byte) i});
            for (int i = 0; i < 4; i++) assertNotNull(mailbox.pollDispatch());

            mailbox.complete(4L);
            mailbox.complete(3L);
            mailbox.complete(2L);
            assertEquals(0L, mailbox.snapshot().completionWatermark());

            // Credits from 2/3/4 were refunded, so admission may proceed.
            mailbox.admit(new byte[] {5});
            mailbox.admit(new byte[] {6});
            mailbox.admit(new byte[] {7});

            // But dispatch may not run beyond the bounded sparse gap window
            // while sequence 1 is still outstanding.
            assertNull(mailbox.pollDispatch());

            mailbox.complete(1L);
            assertEquals(4L, mailbox.snapshot().completionWatermark());
            assertEquals(5L, mailbox.pollDispatch().messageSequence());
            assertEquals(6L, mailbox.pollDispatch().messageSequence());
            assertEquals(7L, mailbox.pollDispatch().messageSequence());
        }
    }

    @Test
    void duplicateAndUndispatchedCompletionsFailClosed() {
        try (var mailbox = new LogicalActorProxyMailboxV1(
                4, 8, 32)) {
            mailbox.admit(new byte[] {1});
            mailbox.admit(new byte[] {2});
            var first = mailbox.pollDispatch();

            assertThrows(
                    IllegalStateException.class,
                    () -> mailbox.complete(2L),
                    "queued but undispatched message cannot complete");
            mailbox.complete(first.messageSequence());
            assertThrows(
                    IllegalStateException.class,
                    () -> mailbox.complete(first.messageSequence()),
                    "duplicate completion must not double-refund capacity");
        }
    }

    @Test
    void staleFreezeTicketsCannotPublishOrRollbackNewerRoute() {
        try (var mailbox = new LogicalActorProxyMailboxV1(
                4, 8, 32)) {
            var first = mailbox.freezeDispatchForUpgrade();
            assertTrue(first.drained());
            first.commit();
            assertEquals(2L, mailbox.snapshot().routeEpoch());

            assertThrows(IllegalStateException.class, first::commit);
            assertThrows(IllegalStateException.class, first::rollback);

            var second = mailbox.freezeDispatchForUpgrade();
            assertEquals(2L, second.routeEpoch());
            second.rollback();
            assertEquals(2L, mailbox.snapshot().routeEpoch());
        }
    }

    @Test
    void admittedPayloadIsImmutableSnapshot() {
        try (var mailbox = new LogicalActorProxyMailboxV1(
                4, 32, 64)) {
            byte[] value = bytes("hello");
            var admitted = mailbox.admit(value);
            value[0] = 'X';

            assertEquals("hello",
                    new String(admitted.payload(), StandardCharsets.UTF_8));
            byte[] exported = admitted.payload();
            exported[0] = 'Y';
            assertEquals('h', admitted.payload()[0]);
        }
    }

    @Test
    void concurrentAdmissionsDuringFreezeRemainUniqueAndOrdered() throws Exception {
        try (var mailbox = new LogicalActorProxyMailboxV1(
                64, 16, 1024);
             var pool = Executors.newFixedThreadPool(8)) {
            var freeze = mailbox.freezeDispatchForUpgrade();
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<LogicalActorProxyMailboxV1.Envelope>>
                    futures = new ArrayList<>();

            for (int i = 0; i < 32; i++) {
                final int value = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return mailbox.admit(new byte[] {(byte) value});
                }));
            }
            start.countDown();

            java.util.TreeSet<Long> sequences = new java.util.TreeSet<>();
            for (var future : futures) {
                sequences.add(
                        future.get(5, TimeUnit.SECONDS).messageSequence());
            }
            assertEquals(32, sequences.size());
            assertEquals(1L, sequences.first());
            assertEquals(32L, sequences.last());
            assertEquals(32, mailbox.snapshot().queuedMessages());
            assertNull(mailbox.pollDispatch());

            assertTrue(freeze.drained());
            freeze.commit();

            for (long expected = 1; expected <= 32; expected++) {
                assertEquals(expected, mailbox.pollDispatch().messageSequence());
            }
        }
    }

    @Test
    void closingClearsRetainedCapacityAndInvalidatesTickets() {
        var mailbox = new LogicalActorProxyMailboxV1(
                4, 16, 64);
        mailbox.admit(new byte[8]);
        mailbox.pollDispatch();
        var freeze = mailbox.freezeDispatchForUpgrade();

        mailbox.close();
        var snapshot = mailbox.snapshot();
        assertTrue(snapshot.closed());
        assertEquals(0, snapshot.retainedMessages());
        assertEquals(0L, snapshot.retainedBytes());
        assertThrows(IllegalStateException.class, freeze::commit);
        assertThrows(
                IllegalStateException.class,
                () -> mailbox.admit(new byte[] {1}));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
