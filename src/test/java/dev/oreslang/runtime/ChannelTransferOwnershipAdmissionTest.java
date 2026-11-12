package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-runtime half of transactional channel payload admission.
 *
 * A Java object identity is only an identity oracle. These tests prove that
 * ChannelRuntime does not enqueue, duplicate, or steal an uncommitted payload;
 * they do NOT prove Atto's guest-level move/Result recovery contract.
 * The guest's writech typing/lowering and cancellation ownership must be
 * tested separately before claiming recoverable sends or backend parity.
 */
@Timeout(10)
final class ChannelTransferOwnershipAdmissionTest {
    private record Payload(int id) { }

    @Test
    void failedFullImmediateProbeDoesNotConsumeOrReorderPayload() {
        ChannelRuntime.Channel<Payload> inbox = new ChannelRuntime.Channel<>(1);
        Payload committed = new Payload(1);
        Payload rejected = new Payload(2);

        assertTrue(inbox.tryWrite(committed));
        assertFalse(inbox.tryWrite(rejected), "full channel must reject without enqueue");
        assertSame(committed, inbox.tryRead().orElseThrow(),
                "failed producer cannot displace a committed payload");
        assertTrue(inbox.tryRead().isEmpty(), "failed probe must not enqueue");
        assertTrue(inbox.tryWrite(rejected),
                "caller must retain the exact failed payload for a later attempt");
        assertSame(rejected, inbox.tryRead().orElseThrow());
        assertTrue(inbox.tryRead().isEmpty(), "no double delivery");
    }

    @Test
    void cancellationOfPendingWriteDoesNotCommitAfterSpaceOpens() {
        ChannelRuntime.Channel<Payload> inbox = new ChannelRuntime.Channel<>(1);
        Payload first = new Payload(1);
        Payload canceled = new Payload(2);
        assertTrue(inbox.tryWrite(first));

        OresFuture<Void> pending = inbox.writeAsync(canceled);
        assertFalse(pending.isDone());
        assertTrue(pending.cancel(false));
        assertTrue(pending.isCancelled());

        assertSame(first, inbox.tryRead().orElseThrow());
        assertTrue(inbox.tryRead().isEmpty(),
                "canceled pending writer must not publish after cancellation");
        assertTrue(inbox.tryWrite(canceled),
                "host caller still owns a reference; guest recoverability is a separate gate");
        assertSame(canceled, inbox.tryRead().orElseThrow());
        assertTrue(inbox.tryRead().isEmpty());
    }

    @Test
    void closeOfPendingWriteFailsRatherThanPublishingPayload() {
        ChannelRuntime.Channel<Payload> inbox = new ChannelRuntime.Channel<>(0);
        Payload candidate = new Payload(3);
        OresFuture<Void> pending = inbox.writeAsync(candidate);
        assertFalse(pending.isDone());

        inbox.close();
        assertTrue(pending.isDone(), "closed channel must settle pending writer");
        assertThrows(RuntimeException.class, pending::join,
                "closed write must not produce a successful commit");
    }

    @Test
    void successfulRendezvousMovesExactPayloadOnce() {
        ChannelRuntime.Channel<Payload> inbox = new ChannelRuntime.Channel<>(0);
        OresFuture<Payload> reader = inbox.readAsync();
        Payload accepted = new Payload(4);

        assertTrue(inbox.tryWrite(accepted));
        assertSame(accepted, reader.join(),
                "paired rendezvous must deliver the original payload by identity");
        assertTrue(inbox.tryRead().isEmpty(),
                "successful rendezvous must not leave a second buffered copy");
    }
    @Test
    void ownedImmediateRejectionReturnsExactPayloadWithoutEnqueue() {
        ChannelRuntime.Channel<Payload> inbox = new ChannelRuntime.Channel<>(1);
        Payload first = new Payload(10);
        Payload second = new Payload(11);
        assertTrue(inbox.tryWriteOwned(first).committed());

        ChannelRuntime.OwnedTryWriteResult<Payload> rejected =
                inbox.tryWriteOwned(second);
        assertEquals(ChannelRuntime.OwnedWriteOutcome.BACKPRESSURE,
                rejected.outcome());
        assertFalse(rejected.committed());
        assertSame(second, rejected.returnedPayload());
        assertSame(first, inbox.tryRead().orElseThrow());
        assertTrue(inbox.tryRead().isEmpty());

        ChannelRuntime.OwnedTryWriteResult<Payload> retry =
                inbox.tryWriteOwned(rejected.returnedPayload());
        assertEquals(ChannelRuntime.OwnedWriteOutcome.COMMITTED,
                retry.outcome());
        assertTrue(retry.committed());
        assertNull(retry.returnedPayload(), "committed send cannot retain payload");
        assertSame(second, inbox.tryRead().orElseThrow());
        assertTrue(inbox.tryRead().isEmpty());
    }

    @Test
    void ownedImmediateClosedRejectionReturnsExactPayload() {
        ChannelRuntime.Channel<Payload> inbox = new ChannelRuntime.Channel<>(1);
        Payload original = new Payload(12);
        inbox.close();

        ChannelRuntime.OwnedTryWriteResult<Payload> rejected =
                inbox.tryWriteOwned(original);
        assertEquals(ChannelRuntime.OwnedWriteOutcome.CLOSED, rejected.outcome());
        assertFalse(rejected.committed());
        assertSame(original, rejected.returnedPayload());
    }

    @Test
    void ownedImmediateRendezvousReturnsOnlyCommitReceipt() {
        ChannelRuntime.Channel<Payload> inbox = new ChannelRuntime.Channel<>(0);
        Payload original = new Payload(13);

        ChannelRuntime.OwnedTryWriteResult<Payload> pending =
                inbox.tryWriteOwned(original);
        assertEquals(ChannelRuntime.OwnedWriteOutcome.BACKPRESSURE,
                pending.outcome());
        assertSame(original, pending.returnedPayload());

        OresFuture<Payload> reader = inbox.readAsync();
        ChannelRuntime.OwnedTryWriteResult<Payload> committed =
                inbox.tryWriteOwned(pending.returnedPayload());
        assertEquals(ChannelRuntime.OwnedWriteOutcome.COMMITTED,
                committed.outcome());
        assertNull(committed.returnedPayload());
        assertSame(original, reader.join());
        assertTrue(inbox.tryRead().isEmpty());
    }

    @Test
    void directionalWriterExposesSameRecoverableImmediateContract() {
        ChannelRuntime.Channel<Payload> inbox = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.WriteChannel<Payload> writer = inbox.input();
        Payload first = new Payload(14);
        Payload second = new Payload(15);

        assertTrue(writer.tryWriteOwned(first).committed());
        assertSame(second, writer.tryWriteOwned(second).returnedPayload());
        assertSame(first, inbox.tryRead().orElseThrow());
        inbox.close();
        ChannelRuntime.OwnedTryWriteResult<Payload> rejected =
                writer.tryWriteOwned(second);
        assertEquals(ChannelRuntime.OwnedWriteOutcome.CLOSED, rejected.outcome());
        assertSame(second, rejected.returnedPayload());
    }

    @Test
    void ownedResultCannotBeForgedThroughPublicConstructors() {
        java.lang.reflect.Constructor<?>[] constructors =
                ChannelRuntime.OwnedTryWriteResult.class.getDeclaredConstructors();
        assertTrue(constructors.length >= 1);
        for (java.lang.reflect.Constructor<?> constructor : constructors) {
            assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()),
                    "owned admission receipt creation must stay runtime-private");
        }
        ChannelRuntime.Channel<Payload> inbox = new ChannelRuntime.Channel<>(1);
        Payload payload = new Payload(16);
        ChannelRuntime.OwnedTryWriteResult<Payload> result =
                inbox.tryWriteOwned(payload);
        assertTrue(result.committed());
        assertNull(result.returnedPayload());
        assertSame(payload, inbox.tryRead().orElseThrow());
    }

    @Test
    void concurrentCloseVersusOwnedImmediateSendHasExactlyOneOutcome()
            throws InterruptedException {
        for (int iteration = 0; iteration < 64; iteration++) {
            ChannelRuntime.Channel<Payload> inbox = new ChannelRuntime.Channel<>(1);
            Payload original = new Payload(1000 + iteration);
            java.util.concurrent.CountDownLatch ready =
                    new java.util.concurrent.CountDownLatch(2);
            java.util.concurrent.CountDownLatch start =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<
                    ChannelRuntime.OwnedTryWriteResult<Payload>> result =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> fault =
                    new java.util.concurrent.atomic.AtomicReference<>();

            Thread sender = Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    start.await();
                    result.set(inbox.tryWriteOwned(original));
                } catch (Throwable failure) {
                    fault.compareAndSet(null, failure);
                }
            });
            Thread closer = Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    start.await();
                    inbox.close();
                } catch (Throwable failure) {
                    fault.compareAndSet(null, failure);
                }
            });
            ready.await();
            start.countDown();
            sender.join();
            closer.join();

            assertNull(fault.get(), "no completion may fail unexpectedly");
            ChannelRuntime.OwnedTryWriteResult<Payload> outcome = result.get();
            assertNotNull(outcome);
            java.util.List<Payload> drained = new java.util.ArrayList<>();
            boolean wasCommitted = inbox.drainOne(drained::add);
            assertFalse(inbox.drainOne(drained::add), "at-most-once delivery");
            if (outcome.committed()) {
                assertEquals(ChannelRuntime.OwnedWriteOutcome.COMMITTED,
                        outcome.outcome());
                assertNull(outcome.returnedPayload());
                assertTrue(wasCommitted, "committed writer must own one buffered value");
                assertEquals(1, drained.size());
                assertSame(original, drained.getFirst());
            } else {
                assertEquals(ChannelRuntime.OwnedWriteOutcome.CLOSED,
                        outcome.outcome(), "empty-capacity-one has no backpressure");
                assertSame(original, outcome.returnedPayload());
                assertFalse(wasCommitted, "rejected send cannot have published");
                assertTrue(drained.isEmpty());
            }
        }
    }

}
