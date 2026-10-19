package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class UnaryTcpActorDispatcherJUnitTest {
    @Test
    void synchronousActorResultPublishesOnlyAfterCarrierFinalizationBarrier()
            throws Exception {
        CountDownLatch turnReturned = new CountDownLatch(1);
        CountDownLatch releaseCarrier = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<UnaryTcpSessionAdapter.Reply> reply = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ActorRuntime.TurnExecutor gated = turn -> {
            turn.run();
            turnReturned.countDown();
            await(releaseCarrier);
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                gated)) {
            var dispatcher = new UnaryTcpActorDispatcher(
                    runtime,
                    ActorRuntime.ActorKind.SHARED,
                    (message, context) -> {
                        assertEquals("peer:test", message.get("peer"));
                        assertEquals(31L, message.get("session_id"));
                        assertEquals(7L, message.get("generation"));
                        assertEquals(99L, message.get("correlation_id"));
                        assertEquals(OresBytes.copyOf(new byte[] {9, 8}), message.get("payload"));
                        return OresBytes.copyOf(new byte[] {1, 2, (byte) 255});
                    },
                    UnaryTcpActorDispatcher.bytesCodec(16));

            var handle = dispatcher.dispatch(new UnaryTcpSessionAdapter.Request(
                    new UnaryTcpSessionAdapter.PeerIdentity("peer:test"),
                    31, 7, 99, new byte[] {9, 8}));
            handle.whenComplete((value, terminal) -> {
                reply.set(value);
                failure.set(terminal);
                completed.countDown();
            });

            assertTrue(turnReturned.await(2, TimeUnit.SECONDS));
            assertEquals(1L, completed.getCount(),
                    "transport completion must not beat ActorRuntime finalization/carrier leave");

            releaseCarrier.countDown();

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertNotNull(reply.get());
            assertEquals(UnaryTransportFrame.Kind.REPLY, reply.get().kind());
            assertArrayEquals(new byte[] {1, 2, (byte) 255}, reply.get().payload());
            assertEquals(0, runtime.actorCount());
        } finally {
            releaseCarrier.countDown();
        }
    }

    @Test
    void fullWireBudgetCrossesActorMailboxAsOneCompactBytesNode() throws Exception {
        byte[] payload = new byte[UnaryTransportFrame.MAX_PAYLOAD_BYTES];
        payload[0] = (byte) 255;
        payload[payload.length - 1] = 7;
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<UnaryTcpSessionAdapter.Reply> reply = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var dispatcher = new UnaryTcpActorDispatcher(
                    runtime,
                    ActorRuntime.ActorKind.SHARED,
                    (message, context) -> {
                        Object value = message.get("payload");
                        assertInstanceOf(OresBytes.class, value);
                        OresBytes bytes = (OresBytes) value;
                        assertEquals(UnaryTransportFrame.MAX_PAYLOAD_BYTES, bytes.size());
                        assertEquals(255, bytes.unsignedAt(0));
                        assertEquals(7, bytes.unsignedAt(bytes.size() - 1));
                        return bytes;
                    },
                    UnaryTcpActorDispatcher.bytesCodec(
                            UnaryTransportFrame.MAX_PAYLOAD_BYTES));

            var handle = dispatcher.dispatch(new UnaryTcpSessionAdapter.Request(
                    new UnaryTcpSessionAdapter.PeerIdentity("peer:large-bytes"),
                    51, 9, 1, payload));
            handle.whenComplete((value, terminal) -> {
                reply.set(value);
                failure.set(terminal);
                completed.countDown();
            });

            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertNotNull(reply.get());
            assertEquals(UnaryTransportFrame.MAX_PAYLOAD_BYTES, reply.get().payload().length);
            assertEquals((byte) 255, reply.get().payload()[0]);
            assertEquals((byte) 7, reply.get().payload()[reply.get().payload().length - 1]);
        }
    }

    @Test
    void transportCancellationCancelsActorOwnedAsyncResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch producerCancelled = new CountDownLatch(1);
        CountDownLatch transportCompleted = new CountDownLatch(1);
        AtomicReference<Throwable> transportFailure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var dispatcher = new UnaryTcpActorDispatcher(
                    runtime,
                    ActorRuntime.ActorKind.SHARED,
                    (message, context) -> {
                        entered.countDown();
                        return new OresFuture<Object>(producerCancelled::countDown);
                    },
                    UnaryTcpActorDispatcher.bytesCodec(16));

            var handle = dispatcher.dispatch(new UnaryTcpSessionAdapter.Request(
                    new UnaryTcpSessionAdapter.PeerIdentity("peer:test"),
                    41, 8, 1, new byte[0]));
            handle.whenComplete((value, terminal) -> {
                transportFailure.set(terminal);
                transportCompleted.countDown();
            });

            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(handle.cancel(), "host cancellation must own the live one-shot actor");
            assertTrue(producerCancelled.await(2, TimeUnit.SECONDS),
                    "actor cancellation must revoke its pending async producer");
            assertTrue(transportCompleted.await(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    java.util.concurrent.CancellationException.class,
                    OresFuture.unwrap(transportFailure.get()));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (runtime.actorCount() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void byteCodecIsLosslessAndRejectsNonIntegralOrOutOfRangeValues() throws Exception {
        var codec = UnaryTcpActorDispatcher.bytesCodec(5);

        assertArrayEquals(
                new byte[] {(byte) 255, 0, 1, (byte) 254, (byte) 255},
                codec.encode(List.of(
                        (byte) -1,
                        (short) 0,
                        1,
                        254L,
                        BigInteger.valueOf(255))));

        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(List.of(1.0d)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(List.of(1.0f)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(List.of(BigInteger.valueOf(256))));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(List.of(-1L)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(List.of("1")));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(Arrays.asList(0L, 1L, 2L, 3L, 4L, 5L)));
        assertThrows(IllegalArgumentException.class,
                () -> UnaryTcpActorDispatcher.bytesCodec(
                        UnaryTransportFrame.MAX_PAYLOAD_BYTES + 1));
    }

    @Test
    void untrustedActorTransportBindingRemainsFailClosed() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            assertThrows(SecurityException.class,
                    () -> new UnaryTcpActorDispatcher(
                            runtime,
                            ActorRuntime.ActorKind.UNTRUSTED,
                            (Map<String, Object> message, ActorRuntime.ActorContext<Map<String, Object>> context)
                                    -> List.of(),
                            UnaryTcpActorDispatcher.byteListCodec(16)));
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try {
                latch.await();
                break;
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
