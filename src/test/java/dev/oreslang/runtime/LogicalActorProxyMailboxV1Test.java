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
    void nonCachedSequenceKeysPreserveAttemptLedgerAndSparseWatermarks() {
        try (var mailbox = new LogicalActorProxyMailboxV1(4, 8, 32)) {
            for (int i = 1; i <= 132; i++) {
                var envelope = mailbox.admit(new byte[] {(byte) i});
                assertEquals(i, envelope.messageSequence());
                assertSame(envelope, mailbox.pollDispatch());
                mailbox.beginPhysicalSendAttempt(envelope, 1L);
                LogicalActorProxyMailboxTestAccess.complete(mailbox, envelope.messageSequence());
            }
            assertEquals(132L, mailbox.snapshot().completionWatermark());

            var a = mailbox.admit(new byte[] {1});
            var b = mailbox.admit(new byte[] {2});
            var c = mailbox.admit(new byte[] {3});
            for (var envelope : new LogicalActorProxyMailboxV1.Envelope[] {a, b, c}) {
                assertSame(envelope, mailbox.pollDispatch());
                mailbox.beginPhysicalSendAttempt(envelope, 1L);
            }
            LogicalActorProxyMailboxTestAccess.complete(mailbox, c.messageSequence());
            LogicalActorProxyMailboxTestAccess.complete(mailbox, b.messageSequence());
            assertEquals(132L, mailbox.snapshot().completionWatermark());
            assertEquals(2, mailbox.snapshot().sparseCompletedMessages());
            LogicalActorProxyMailboxTestAccess.complete(mailbox, a.messageSequence());
            assertEquals(135L, mailbox.snapshot().completionWatermark());
            assertEquals(0, mailbox.snapshot().sparseCompletedMessages());

            var later = mailbox.admit(new byte[] {4});
            mailbox.pollDispatch();
            mailbox.beginPhysicalSendAttempt(later, 1L);
            // A bare sequence number is never proof that the physical
            // actor mailbox rejected an attempted send, including >127.
            var retained = mailbox.snapshot();
            assertThrows(IllegalStateException.class,
                    () -> LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, later.messageSequence()));
            assertEquals(retained, mailbox.snapshot());
            LogicalActorProxyMailboxTestAccess.complete(mailbox, later.messageSequence());
            assertEquals(136L, mailbox.snapshot().completionWatermark());
            assertEquals(0, mailbox.snapshot().retainedMessages());
        }
    }

    @Test
    void physicalAdmissionReservationRejectsReplayAndUnprovenRequeue() {
        try (var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32)) {
            var envelope = mailbox.admit(bytes("first"));
            assertEquals(envelope, mailbox.pollDispatch());
            mailbox.beginPhysicalSendAttempt(envelope, 1L);
            var charged = mailbox.snapshot();
            assertThrows(IllegalStateException.class,
                    () -> mailbox.beginPhysicalSendAttempt(envelope, 1L),
                    "an unresolved physical send attempt must not be submitted twice");
            assertEquals(charged, mailbox.snapshot());
            var freeze = mailbox.freezeDispatchForUpgrade();
            assertThrows(IllegalStateException.class,
                    () -> mailbox.beginPhysicalSendAttempt(envelope, 1L),
                    "frozen dispatch may only settle already attempted sends");
            freeze.rollback();
            // Package-private authority is not an authenticated physical
            // non-acceptance receipt. Retain the attempt until terminal
            // completion; never restore bytes to the dispatch queue.
            assertThrows(IllegalStateException.class,
                    () -> LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, envelope.messageSequence()));
            assertEquals(charged, mailbox.snapshot());
            assertEquals(0, mailbox.snapshot().queuedMessages());
            LogicalActorProxyMailboxTestAccess.complete(mailbox, envelope.messageSequence());
            assertEquals(0, mailbox.snapshot().retainedMessages());
            assertThrows(SecurityException.class,
                    () -> mailbox.beginPhysicalSendAttempt(envelope, 1L));
        }
    }

    @Test
    void sequenceOnlyRequeueWorksBeforeButNotAfterPhysicalSendReservation() {
        try (var mailbox = new LogicalActorProxyMailboxV1(2, 8, 16)) {
            var envelope = mailbox.admit(new byte[] {41});
            assertSame(envelope, mailbox.pollDispatch());
            LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, envelope.messageSequence());
            assertEquals(1, mailbox.snapshot().queuedMessages());

            assertSame(envelope, mailbox.pollDispatch());
            mailbox.beginPhysicalSendAttempt(envelope, 1L);
            var attempted = mailbox.snapshot();
            for (int retry = 0; retry < 4; ++retry) {
                assertThrows(IllegalStateException.class,
                        () -> LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, envelope.messageSequence()));
                assertEquals(attempted, mailbox.snapshot());
            }
            var freeze = mailbox.freezeDispatchForUpgrade();
            assertFalse(freeze.drained());
            freeze.rollback();
            assertThrows(IllegalStateException.class,
                    () -> LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, envelope.messageSequence()));
            LogicalActorProxyMailboxTestAccess.complete(mailbox, envelope.messageSequence());
            assertEquals(1L, mailbox.snapshot().completionWatermark());
            assertEquals(0, mailbox.snapshot().retainedMessages());
        }
    }

    @Test
    void sendReservationRacingRequeueCanNeverAdmitDuplicatePhysicalWork()
            throws Exception {
        for (int round = 0; round < 128; round++) {
            try (var mailbox = new LogicalActorProxyMailboxV1(2, 8, 16)) {
                var envelope = mailbox.admit(new byte[] {1});
                assertSame(envelope, mailbox.pollDispatch());

                var go = new CountDownLatch(1);
                var reserved = new java.util.concurrent.atomic.AtomicBoolean();
                var requeued = new java.util.concurrent.atomic.AtomicBoolean();
                var unexpected = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread sender = Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        mailbox.beginPhysicalSendAttempt(envelope, 1L);
                        reserved.set(true);
                    } catch (SecurityException | IllegalStateException lostRace) {
                        // Requeue won before the physical send reservation.
                    } catch (Throwable bad) {
                        unexpected.compareAndSet(null, bad);
                    }
                });
                Thread retry = Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, envelope.messageSequence());
                        requeued.set(true);
                    } catch (IllegalStateException lostRace) {
                        // Physical attempt won and may already be accepted.
                    } catch (Throwable bad) {
                        unexpected.compareAndSet(null, bad);
                    }
                });
                go.countDown();
                sender.join(5000);
                retry.join(5000);
                assertFalse(sender.isAlive() || retry.isAlive());
                assertNull(unexpected.get());
                assertNotEquals(reserved.get(), requeued.get(),
                        "exactly one of physical reservation and requeue must win");
                if (reserved.get()) {
                    assertEquals(1, mailbox.snapshot().outstandingMessages());
                    assertEquals(0, mailbox.snapshot().queuedMessages());
                    assertThrows(IllegalStateException.class,
                            () -> LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, 
                                    envelope.messageSequence()));
                } else {
                    assertEquals(0, mailbox.snapshot().outstandingMessages());
                    assertEquals(1, mailbox.snapshot().queuedMessages());
                    assertSame(envelope, mailbox.pollDispatch());
                }
                LogicalActorProxyMailboxTestAccess.complete(mailbox, envelope.messageSequence());
                assertEquals(0, mailbox.snapshot().retainedMessages());
                assertEquals(1L, mailbox.snapshot().completionWatermark());
            }
        }
    }

    @Test
    void concurrentPhysicalSendReservationsAdmitExactlyOneSender()
            throws Exception {
        try (var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32)) {
            var envelope = mailbox.admit(bytes("one"));
            mailbox.pollDispatch();
            var start = new CountDownLatch(1);
            var admitted = new java.util.concurrent.atomic.AtomicInteger();
            var unexpected = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread[] callers = new Thread[32];
            for (int i = 0; i < callers.length; i++) {
                callers[i] = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        mailbox.beginPhysicalSendAttempt(envelope, 1L);
                        admitted.incrementAndGet();
                    } catch (IllegalStateException expectedDuplicate) {
                        // The winning sender owns the single physical attempt.
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                });
            }
            start.countDown();
            for (Thread caller : callers) {
                caller.join(5000);
                assertFalse(caller.isAlive());
            }
            assertNull(unexpected.get());
            assertEquals(1, admitted.get());
            assertEquals(1, mailbox.snapshot().outstandingMessages());
            LogicalActorProxyMailboxTestAccess.complete(mailbox, envelope.messageSequence());
            assertEquals(0, mailbox.snapshot().retainedMessages());
        }
    }

    @Test
    void physicalReceiptRequiresOriginalOutstandingEnvelopeAndEpoch() {
        try (var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32);
             var foreign = new LogicalActorProxyMailboxV1(2, 16, 32)) {
            var admitted = mailbox.admit(bytes("one"));
            var foreignEnvelope = foreign.admit(bytes("one"));
            var before = mailbox.snapshot();
            assertThrows(SecurityException.class,
                    () -> mailbox.requireOutstandingEnvelope(admitted, 1L),
                    "queued but not physically dispatched is not a completion receipt");
            assertEquals(before, mailbox.snapshot());

            var dispatched = mailbox.pollDispatch();
            assertSame(admitted, dispatched);
            mailbox.requireOutstandingEnvelope(dispatched, 1L);
            var copied = new LogicalActorProxyMailboxV1.Envelope(
                    dispatched.messageSequence(), dispatched.payload());
            assertThrows(SecurityException.class,
                    () -> mailbox.requireOutstandingEnvelope(copied, 1L));
            assertThrows(SecurityException.class,
                    () -> mailbox.requireOutstandingEnvelope(foreignEnvelope, 1L));
            assertThrows(SecurityException.class,
                    () -> mailbox.requireOutstandingEnvelope(dispatched, 2L));

            // A legitimate drain may settle old mail during dispatch freeze.
            var ticket = mailbox.freezeDispatchForUpgrade();
            mailbox.requireOutstandingEnvelope(dispatched, 1L);
            LogicalActorProxyMailboxTestAccess.complete(mailbox, dispatched.messageSequence());
            assertThrows(SecurityException.class,
                    () -> mailbox.requireOutstandingEnvelope(dispatched, 1L));
            assertTrue(ticket.drained());
            ticket.commit();
            assertThrows(SecurityException.class,
                    () -> mailbox.requireOutstandingEnvelope(dispatched, 2L));
            assertEquals(0, mailbox.snapshot().retainedMessages());
        }
    }

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

            LogicalActorProxyMailboxTestAccess.complete(mailbox, 2L);
            assertFalse(freeze.drained(),
                    "message 1 still belongs to the old route prefix");
            LogicalActorProxyMailboxTestAccess.complete(mailbox, 1L);
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
            LogicalActorProxyMailboxTestAccess.complete(mailbox, 1L);
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
            LogicalActorProxyMailboxTestAccess.complete(mailbox, 2L);
            LogicalActorProxyMailboxTestAccess.complete(mailbox, 1L);
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
            LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, second.messageSequence());
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
            LogicalActorProxyMailboxTestAccess.complete(mailbox, first.messageSequence());
            LogicalActorProxyMailboxTestAccess.complete(mailbox, second.messageSequence());
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
                    () -> LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, first.messageSequence()));
            assertEquals(before, mailbox.snapshot(),
                    "rejected older rollback cannot change queue or credits");

            var freeze = mailbox.freezeDispatchForUpgrade();
            assertThrows(IllegalStateException.class,
                    () -> LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, second.messageSequence()));
            freeze.rollback();
            LogicalActorProxyMailboxTestAccess.complete(mailbox, second.messageSequence());
            assertThrows(IllegalStateException.class,
                    () -> LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, second.messageSequence()),
                    "completed sends must never be admitted again");
            LogicalActorProxyMailboxTestAccess.complete(mailbox, first.messageSequence());
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
            LogicalActorProxyMailboxTestAccess.complete(mailbox, 2L);
            var before = mailbox.snapshot();
            assertThrows(IllegalStateException.class,
                    () -> LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, 2L));
            assertEquals(before, mailbox.snapshot());
            LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, last.messageSequence());
            assertEquals(2L, mailbox.snapshot().highestDispatchedSequence());
            assertEquals(1L, mailbox.snapshot().queuedMessages());
            assertEquals(3L, mailbox.pollDispatch().messageSequence());
            LogicalActorProxyMailboxTestAccess.complete(mailbox, 1L);
            LogicalActorProxyMailboxTestAccess.complete(mailbox, 3L);
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

            LogicalActorProxyMailboxTestAccess.complete(mailbox, dispatched.messageSequence());
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

            LogicalActorProxyMailboxTestAccess.complete(mailbox, 4L);
            LogicalActorProxyMailboxTestAccess.complete(mailbox, 3L);
            LogicalActorProxyMailboxTestAccess.complete(mailbox, 2L);
            assertEquals(0L, mailbox.snapshot().completionWatermark());

            // Credits from 2/3/4 were refunded, so admission may proceed.
            mailbox.admit(new byte[] {5});
            mailbox.admit(new byte[] {6});
            mailbox.admit(new byte[] {7});

            // But dispatch may not run beyond the bounded sparse gap window
            // while sequence 1 is still outstanding.
            assertNull(mailbox.pollDispatch());

            LogicalActorProxyMailboxTestAccess.complete(mailbox, 1L);
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

            LogicalActorProxyMailboxTestAccess.complete(mailbox, 3L);
            var before = mailbox.snapshot();
            assertEquals(1, before.sparseCompletedMessages());
            assertEquals(2, before.retainedMessages());
            assertEquals(2, before.outstandingMessages());

            assertThrows(
                    IllegalStateException.class,
                    () -> LogicalActorProxyMailboxTestAccess.complete(mailbox, 99L));

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
                    () -> LogicalActorProxyMailboxTestAccess.complete(mailbox, 2L),
                    "queued but undispatched message cannot complete");
            LogicalActorProxyMailboxTestAccess.complete(mailbox, first.messageSequence());
            assertThrows(
                    IllegalStateException.class,
                    () -> LogicalActorProxyMailboxTestAccess.complete(mailbox, first.messageSequence()),
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
                            LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, 1L);
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
                    LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, 1L);
                }
                assertEquals(1L, mailbox.pollDispatch().messageSequence());
                LogicalActorProxyMailboxTestAccess.complete(mailbox, 1L);
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
            LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, 1L);
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
            LogicalActorProxyMailboxTestAccess.complete(mailbox, 2L);
            LogicalActorProxyMailboxTestAccess.complete(mailbox, 3L);
            assertEquals(0L, mailbox.snapshot().completionWatermark());
            LogicalActorProxyMailboxTestAccess.complete(mailbox, 1L);
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
                    () -> LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, 1L),
                    "only the most recent dispatch can be proven unsent");

            var freeze = mailbox.freezeDispatchForUpgrade();
            assertThrows(IllegalStateException.class,
                    () -> LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, 2L),
                    "a frozen dispatch watermark cannot be rolled backwards");
            assertFalse(freeze.drained());
            freeze.rollback();
            LogicalActorProxyMailboxTestAccess.complete(mailbox, 2L);
            assertThrows(IllegalStateException.class,
                    () -> LogicalActorProxyMailboxTestAccess.requeueBySequence(mailbox, 2L),
                    "a completed delivery must never be replayed");
            LogicalActorProxyMailboxTestAccess.complete(mailbox, 1L);
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

            LogicalActorProxyMailboxTestAccess.complete(mailbox, outstanding.messageSequence());
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
    // Simulate a corrupted ledger only in a test. The check must run BEFORE
    // any irreversible route epoch or dispatch-freeze mutation.
    private static void setRetainedCountForCorruptionTest(
            LogicalActorProxyMailboxV1 mailbox, int count) throws Exception {
        var field = LogicalActorProxyMailboxV1.class.getDeclaredField("retainedMessages");
        field.setAccessible(true);
        field.setInt(mailbox, count);
    }

    @Test
    void failedInvariantCheckCannotThawCommittedRoute() throws Exception {
        try (var mailbox = new LogicalActorProxyMailboxV1(4, 8, 32)) {
            var ticket = mailbox.freezeDispatchForUpgrade();
            setRetainedCountForCorruptionTest(mailbox, 1);
            try {
                assertThrows(IllegalStateException.class, ticket::commit);
            } finally {
                setRetainedCountForCorruptionTest(mailbox, 0);
            }
            assertEquals(1L, mailbox.snapshot().routeEpoch());
            assertTrue(mailbox.snapshot().dispatchFrozen());
            ticket.rollback();
        }
    }

    @Test
    void failedInvariantCheckCannotThawRolledBackRoute() throws Exception {
        try (var mailbox = new LogicalActorProxyMailboxV1(4, 8, 32)) {
            var ticket = mailbox.freezeDispatchForUpgrade();
            setRetainedCountForCorruptionTest(mailbox, 1);
            try {
                assertThrows(IllegalStateException.class, ticket::rollback);
            } finally {
                setRetainedCountForCorruptionTest(mailbox, 0);
            }
            assertEquals(1L, mailbox.snapshot().routeEpoch());
            assertTrue(mailbox.snapshot().dispatchFrozen());
            ticket.rollback();
        }
    }

    @Test
    void failedInvariantCheckCannotCreateOrphanFreeze() throws Exception {
        try (var mailbox = new LogicalActorProxyMailboxV1(4, 8, 32)) {
            setRetainedCountForCorruptionTest(mailbox, 1);
            try {
                assertThrows(IllegalStateException.class,
                        mailbox::freezeDispatchForUpgrade);
            } finally {
                setRetainedCountForCorruptionTest(mailbox, 0);
            }
            assertFalse(mailbox.snapshot().dispatchFrozen());
            var ticket = mailbox.freezeDispatchForUpgrade();
            ticket.rollback();
        }
    }

}
