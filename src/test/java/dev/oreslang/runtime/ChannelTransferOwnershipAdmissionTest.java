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
}
