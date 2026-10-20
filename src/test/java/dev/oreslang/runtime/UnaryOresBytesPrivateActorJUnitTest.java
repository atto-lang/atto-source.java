package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class UnaryOresBytesPrivateActorJUnitTest {
    @Test
    void fullUnaryBudgetCrossesPrivateActorMailboxAsCompactBytes() throws Exception {
        byte[] payload = new byte[UnaryTransportFrame.MAX_PAYLOAD_BYTES];
        payload[0] = 11;
        payload[payload.length - 1] = (byte) 251;
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<OresBytes> actorResult = new AtomicReference<>();
        AtomicReference<UnaryTcpSessionAdapter.Reply> reply = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var dispatcher = new UnaryTcpActorDispatcher(
                    runtime,
                    ActorRuntime.ActorKind.PRIVATE,
                    (message, context) -> {
                        OresBytes bytes = assertInstanceOf(
                                OresBytes.class, message.get("payload"));
                        assertEquals(UnaryTransportFrame.MAX_PAYLOAD_BYTES, bytes.size());
                        assertEquals(11, bytes.unsignedAt(0));
                        assertEquals(251, bytes.unsignedAt(bytes.size() - 1));
                        OresBytes produced = OresBytes.copyOf(new byte[] {7, 8, 9});
                        actorResult.set(produced);
                        return produced;
                    },
                    UnaryTcpActorDispatcher.bytesCodec(3));

            var handle = dispatcher.dispatch(new UnaryTcpSessionAdapter.Request(
                    new UnaryTcpSessionAdapter.PeerIdentity("peer:private-large"),
                    52, 10, 1, payload));
            handle.whenComplete((value, terminal) -> {
                reply.set(value);
                failure.set(terminal);
                completed.countDown();
            });

            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertNotNull(reply.get());
            assertNotNull(actorResult.get());
            assertEquals(actorResult.get(), reply.get().payloadValue());
            assertNotSame(actorResult.get(), reply.get().payloadValue(),
                    "PRIVATE actor result bytes must be copied into supervisor ownership");
        }
    }
}
