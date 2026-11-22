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
    void failedQueueAllocationDoesNotConsumeSequenceOrCapacity() {
        class FailOnceQueue extends java.util.ArrayDeque<
                LogicalActorProxyMailboxV1.Envelope> {
            private boolean failed;
            @Override
            public void addLast(LogicalActorProxyMailboxV1.Envelope envelope) {
                if (!failed) {
                    failed = true;
                    throw new OutOfMemoryError("injected deque expansion failure");
                }
                super.addLast(envelope);
            }
        }
        try (var mailbox = new LogicalActorProxyMailboxV1(
                2, 16, 32, new FailOnceQueue())) {
            var before = mailbox.snapshot();
            assertThrows(OutOfMemoryError.class,
                    () -> mailbox.admit(bytes("first")));
            assertEquals(before, mailbox.snapshot(),
                    "failed insertion may not consume an admission sequence or credit");

            var first = mailbox.admit(bytes("first"));
            assertEquals(1L, first.messageSequence());
            var second = mailbox.admit(bytes("second"));
            assertEquals(2L, second.messageSequence());
            assertEquals(1L, mailbox.pollDispatch().messageSequence());
            assertEquals(2L, mailbox.pollDispatch().messageSequence());
            mailbox.complete(2L);
            mailbox.complete(1L);
            assertEquals(2L, mailbox.snapshot().completionWatermark());
            assertEquals(0, mailbox.snapshot().retainedMessages());
        }
    }

    @Test
    void prepopulatedInjectedQueueCannotBypassBoundedAdmission() {
        var injected = new java.util.ArrayDeque<
                LogicalActorProxyMailboxV1.Envelope>();
        injected.add(new LogicalActorProxyMailboxV1.Envelope(
                1L, bytes("untracked")));
        assertThrows(IllegalArgumentException.class,
                () -> new LogicalActorProxyMailboxV1(2, 16, 32, injected),
                "test-only queue injection must not import unaccounted envelopes");
        assertThrows(NullPointerException.class,
                () -> new LogicalActorProxyMailboxV1(2, 16, 32, null));
    }

    @Test
    void consumedFreezeTicketCloseCannotThawLaterEpoch() {
        try (var mailbox = new LogicalActorProxyMailboxV1(4, 8, 32)) {
            var initial = mailbox.freezeDispatchForUpgrade();
            initial.commit();
            var current = mailbox.freezeDispatchForUpgrade();
            initial.close();
            assertTrue(mailbox.snapshot().dispatchFrozen());
            assertNull(mailbox.pollDispatch());
            assertEquals(2L, current.routeEpoch());
            current.rollback();
            assertFalse(mailbox.snapshot().dispatchFrozen());
        }
    }

    @Test
    void transportRejectionRequeuesLatestUnsentWithoutRefundingCredits() {
        try (var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32)) {
            var first = mailbox.admit(bytes("first"));
            var second = mailbox.admit(bytes("second"));
            assertEquals(first.messageSequence(),
                    mailbox.pollDispatch().messageSequence());
            assertEquals(second.messageSequence(),
                    mailbox.pollDispatch().messageSequence());
            var before = mailbox.snapshot();
            mailbox.requeueProvenUnsent(second.messageSequence());
            var after = mailbox.snapshot();
            assertEquals(before.retainedMessages(), after.retainedMessages());
            assertEquals(before.retainedBytes(), after.retainedBytes());
            assertEquals(1, after.queuedMessages());
            assertEquals(1, after.outstandingMessages());
            assertEquals(1L, after.highestDispatchedSequence());
            assertThrows(IllegalStateException.class,
                    () -> mailbox.admit(new byte[] {3}),
                    "an unsent rollback is not completion and cannot refund capacity");
            assertEquals(second.messageSequence(),
                    mailbox.pollDispatch().messageSequence());
            mailbox.complete(first.messageSequence());
            mailbox.complete(second.messageSequence());
            assertEquals(0, mailbox.snapshot().retainedMessages());
        }
    }

    @Test
    void rollbackOfDispatchedMessageRequiresLatestOutstandingAndNoFreeze() {
        try (var mailbox = new LogicalActorProxyMailboxV1(4, 16, 64)) {
            var first = mailbox.admit(bytes("one"));
            var second = mailbox.admit(bytes("two"));
            mailbox.pollDispatch();
            mailbox.pollDispatch();
            var before = mailbox.snapshot();
            assertThrows(IllegalStateException.class,
                    () -> mailbox.requeueProvenUnsent(first.messageSequence()));
            assertEquals(before, mailbox.snapshot(),
                    "rejected older rollback cannot change queue or credits");

            var freeze = mailbox.freezeDispatchForUpgrade();
            assertThrows(IllegalStateException.class,
                    () -> mailbox.requeueProvenUnsent(second.messageSequence()));
            freeze.rollback();
            mailbox.complete(second.messageSequence());
            assertThrows(IllegalStateException.class,
                    () -> mailbox.requeueProvenUnsent(second.messageSequence()),
                    "completed sends must never be admitted again");
            mailbox.complete(first.messageSequence());
            assertEquals(0, mailbox.snapshot().retainedMessages());
        }
    }

    @Test
    void sparseEarlierCompletionDoesNotPermitReplayingAnAcceptedSend() {
        try (var mailbox = new LogicalActorProxyMailboxV1(4, 16, 64)) {
            for (int i = 0; i < 3; i++) mailbox.admit(new byte[] {(byte) i});
            mailbox.pollDispatch();
            mailbox.pollDispatch();
            var last = mailbox.pollDispatch();
            mailbox.complete(2L);
            var before = mailbox.snapshot();
            assertThrows(IllegalStateException.class,
                    () -> mailbox.requeueProvenUnsent(2L));
            assertEquals(before, mailbox.snapshot());
            mailbox.requeueProvenUnsent(last.messageSequence());
            assertEquals(2L, mailbox.snapshot().highestDispatchedSequence());
            assertEquals(1L, mailbox.snapshot().queuedMessages());
            assertEquals(3L, mailbox.pollDispatch().messageSequence());
            mailbox.complete(1L);
            mailbox.complete(3L);
            assertEquals(3L, mailbox.snapshot().completionWatermark());
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
    void rejectedCompletionCannotPartiallyMutateCreditsOrSparseMetadata() {
        try (var mailbox = new LogicalActorProxyMailboxV1(
                4, 16, 64)) {
            mailbox.admit(new byte[] {1});
            mailbox.admit(new byte[] {2});
            mailbox.admit(new byte[] {3});
            mailbox.pollDispatch();
            mailbox.pollDispatch();
            mailbox.pollDispatch();

            mailbox.complete(3L);
            var before = mailbox.snapshot();
            assertEquals(1, before.sparseCompletedMessages());
            assertEquals(2, before.retainedMessages());
            assertEquals(2, before.outstandingMessages());

            assertThrows(
                    IllegalStateException.class,
                    () -> mailbox.complete(99L));

            var after = mailbox.snapshot();
            assertEquals(before.retainedMessages(), after.retainedMessages());
            assertEquals(before.retainedBytes(), after.retainedBytes());
            assertEquals(before.outstandingMessages(), after.outstandingMessages());
            assertEquals(
                    before.sparseCompletedMessages(),
                    after.sparseCompletedMessages());
            assertEquals(
                    before.completionWatermark(),
                    after.completionWatermark());
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
    void freezeVersusProvenUnsentRollbackPreservesExactCreditsAndSequence()
            throws Exception {
        for (int round = 0; round < 128; round++) {
            try (var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32)) {
                mailbox.admit(new byte[] {42});
                assertEquals(1L, mailbox.pollDispatch().messageSequence());
                var start = new CountDownLatch(1);
                var freeze = new java.util.concurrent.atomic.AtomicReference<
                        LogicalActorProxyMailboxV1.FreezeTicket>();
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread retry = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        try {
                            mailbox.requeueProvenUnsent(1L);
                        } catch (IllegalStateException freezeWon) {
                            // Freeze acquired the lock before the trusted
                            // unsent rollback. It must not mutate the message.
                        }
                    } catch (Throwable unexpected) {
                        failure.compareAndSet(null, unexpected);
                    }
                });
                Thread upgrade = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        freeze.set(mailbox.freezeDispatchForUpgrade());
                    } catch (Throwable unexpected) {
                        failure.compareAndSet(null, unexpected);
                    }
                });
                start.countDown();
                retry.join(5000);
                upgrade.join(5000);
                assertFalse(retry.isAlive() || upgrade.isAlive(),
                        "concurrent proxy freeze/requeue workers must terminate");
                assertNull(failure.get());
                assertNotNull(freeze.get());
                var snapshot = mailbox.snapshot();
                assertTrue(snapshot.dispatchFrozen());
                assertEquals(1, snapshot.retainedMessages());
                assertEquals(1L, snapshot.retainedBytes());
                assertEquals(1, snapshot.queuedMessages()
                        + snapshot.outstandingMessages());
                assertEquals(0L, snapshot.completionWatermark());
                assertEquals(1L,
                        snapshot.queuedMessages() == 1
                                ? snapshot.nextMessageSequence() - 1L
                                : snapshot.highestDispatchedSequence());

                freeze.get().rollback();
                if (snapshot.outstandingMessages() == 1) {
                    mailbox.requeueProvenUnsent(1L);
                }
                assertEquals(1L, mailbox.pollDispatch().messageSequence());
                mailbox.complete(1L);
                assertEquals(1L, mailbox.snapshot().completionWatermark());
                assertEquals(0, mailbox.snapshot().retainedMessages());
                assertEquals(0L, mailbox.snapshot().retainedBytes());
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

    @Test
    void provenUnsentRequeueRetainsCreditsAndOriginalFifoSequence() {
        try (var mailbox = new LogicalActorProxyMailboxV1(3, 8, 24)) {
            mailbox.admit(bytes("a"));
            mailbox.admit(bytes("bb"));
            mailbox.admit(bytes("ccc"));

            assertEquals(1L, mailbox.pollDispatch().messageSequence());
            mailbox.requeueProvenUnsent(1L);
            var requeued = mailbox.snapshot();
            assertEquals(0L, requeued.highestDispatchedSequence());
            assertEquals(3, requeued.queuedMessages());
            assertEquals(0, requeued.outstandingMessages());
            assertEquals(3, requeued.retainedMessages());
            assertEquals(6L, requeued.retainedBytes());
            assertThrows(IllegalStateException.class,
                    () -> mailbox.admit(bytes("d")));

            assertEquals(1L, mailbox.pollDispatch().messageSequence());
            assertEquals(2L, mailbox.pollDispatch().messageSequence());
            assertEquals(3L, mailbox.pollDispatch().messageSequence());
            mailbox.complete(2L);
            mailbox.complete(3L);
            assertEquals(0L, mailbox.snapshot().completionWatermark());
            mailbox.complete(1L);
            assertEquals(3L, mailbox.snapshot().completionWatermark());
            assertEquals(0, mailbox.snapshot().retainedMessages());
            assertEquals(0L, mailbox.snapshot().retainedBytes());
        }
    }

    @Test
    void requeueCannotErasePriorDeliveryOrBypassFreeze() {
        try (var mailbox = new LogicalActorProxyMailboxV1(4, 8, 32)) {
            mailbox.admit(bytes("a"));
            mailbox.admit(bytes("b"));
            mailbox.pollDispatch();
            mailbox.pollDispatch();
            assertThrows(IllegalStateException.class,
                    () -> mailbox.requeueProvenUnsent(1L),
                    "only the most recent dispatch can be proven unsent");

            var freeze = mailbox.freezeDispatchForUpgrade();
            assertThrows(IllegalStateException.class,
                    () -> mailbox.requeueProvenUnsent(2L),
                    "a frozen dispatch watermark cannot be rolled backwards");
            assertFalse(freeze.drained());
            freeze.rollback();
            mailbox.complete(2L);
            assertThrows(IllegalStateException.class,
                    () -> mailbox.requeueProvenUnsent(2L),
                    "a completed delivery must never be replayed");
            mailbox.complete(1L);
            assertEquals(0, mailbox.snapshot().retainedMessages());
        }
    }

    @Test
    void rejectedAdmissionsDoNotAdvanceSequenceOrLoseCredits() {
        try (var mailbox = new LogicalActorProxyMailboxV1(1, 4, 4)) {
            assertThrows(IllegalArgumentException.class,
                    () -> mailbox.admit(new byte[5]));
            assertEquals(1L, mailbox.snapshot().nextMessageSequence());
            assertEquals(1L, mailbox.admit(bytes("ok")).messageSequence());
            assertThrows(IllegalStateException.class,
                    () -> mailbox.admit(bytes("no")));
            assertEquals(2L, mailbox.snapshot().nextMessageSequence());
            assertEquals(2L, mailbox.snapshot().retainedBytes());
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void ambiguousUpgradeCleanupCannotImplicitlyThawDispatch() {
        try (var mailbox = new LogicalActorProxyMailboxV1(3, 8, 24)) {
            var before = mailbox.admit(new byte[] {1});
            var outstanding = mailbox.pollDispatch();
            assertEquals(before.messageSequence(), outstanding.messageSequence());
            var freeze = mailbox.freezeDispatchForUpgrade();
            mailbox.admit(new byte[] {2});
            var frozen = mailbox.snapshot();

            assertThrows(IllegalStateException.class, freeze::close,
                    "ordinary cleanup cannot authorize rollback after uncertain publication");
            assertEquals(frozen, mailbox.snapshot(),
                    "rejected close must retain every credit and the active freeze");
            assertNull(mailbox.pollDispatch(), "old-route dispatch must stay frozen");

            mailbox.complete(outstanding.messageSequence());
            assertTrue(freeze.drained());
            assertThrows(IllegalStateException.class, freeze::close,
                    "even after drain, publication still needs an explicit decision");
            assertTrue(mailbox.snapshot().dispatchFrozen());

            freeze.commit();
            freeze.close(); // idempotent after a successful explicit decision
            assertEquals(2L, mailbox.snapshot().routeEpoch());
            assertEquals(2L, mailbox.pollDispatch().messageSequence());
        }
    }

    @Test
    void explicitFreezeRollbackIsRequiredToRestoreOldDispatch() {
        try (var mailbox = new LogicalActorProxyMailboxV1(2, 8, 16)) {
            var first = mailbox.admit(new byte[] {1});
            var freeze = mailbox.freezeDispatchForUpgrade();
            assertThrows(IllegalStateException.class, freeze::close);
            assertNull(mailbox.pollDispatch());
            freeze.rollback();
            freeze.close(); // cannot affect any subsequent epoch
            assertEquals(first.messageSequence(),
                    mailbox.pollDispatch().messageSequence());
            assertEquals(1L, mailbox.snapshot().routeEpoch());
        }
    }

    @Test
    void concurrentTicketCleanupCannotReverseSuccessfulPublication() throws Exception {
        for (int iteration = 0; iteration < 128; iteration++) {
            try (var mailbox = new LogicalActorProxyMailboxV1(2, 8, 16)) {
                var ticket = mailbox.freezeDispatchForUpgrade();
                var start = new CountDownLatch(1);
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread cleanup = new Thread(() -> {
                    try {
                        start.await();
                        try {
                            ticket.close();
                        } catch (IllegalStateException expectedWhileActive) {
                            // Closing an active ticket must refuse to thaw it.
                        }
                    } catch (Throwable unexpected) {
                        failure.compareAndSet(null, unexpected);
                    }
                }, "proxy-freeze-cleanup");
                Thread publish = new Thread(() -> {
                    try {
                        start.await();
                        ticket.commit();
                    } catch (Throwable unexpected) {
                        failure.compareAndSet(null, unexpected);
                    }
                }, "proxy-freeze-publisher");
                cleanup.start();
                publish.start();
                try {
                    start.countDown();
                    cleanup.join(5000);
                    publish.join(5000);
                    assertFalse(cleanup.isAlive());
                    assertFalse(publish.isAlive());
                    assertNull(failure.get(),
                            "cleanup racing with explicit commit must never alter publication");
                    assertEquals(2L, mailbox.snapshot().routeEpoch());
                    assertFalse(mailbox.snapshot().dispatchFrozen());
                    ticket.close();
                } finally {
                    start.countDown();
                    cleanup.join(5000);
                    publish.join(5000);
                }
            }
        }
    }
}
