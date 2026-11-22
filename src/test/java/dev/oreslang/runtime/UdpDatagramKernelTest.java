package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Physical host UDP loopback tests; NOT evidence of an imported Atto guest API. */
@Timeout(15)
final class UdpDatagramKernelTest {
    private static UdpDatagramKernel bind() throws IOException {
        return UdpDatagramKernel.bindLoopback(0, (resource, operation) -> { });
    }

    private static UdpDatagramKernel.Received poll(UdpDatagramKernel receiver, int max)
            throws Exception {
        for (int i = 0; i < 1000; i++) {
            UdpDatagramKernel.Received result = receiver.tryReceiveFrom(max);
            if (result != null) return result;
            Thread.sleep(1);
        }
        fail("UDP datagram never arrived");
        return null;
    }

    @Test void transportsTwoDistinctDatagramsAndZeroLengthPacket() throws Exception {
        try (var sender = bind(); var receiver = bind()) {
            byte[] first = new byte[] {1, 0, (byte) 0xff, 4};
            assertEquals(UdpDatagramKernel.SendOutcome.SENT,
                    sender.trySendTo(receiver.localPort(), first));
            first[0] = 99; // Host send snapshots ownership.
            var received = poll(receiver, 4);
            assertArrayEquals(new byte[] {1, 0, (byte) 0xff, 4}, received.bytes());
            assertEquals(sender.localPort(), received.sourcePort());
            byte[] leaked = received.bytes();
            leaked[0] = 32;
            assertEquals(1, received.bytes()[0]);
            assertEquals(UdpDatagramKernel.SendOutcome.SENT,
                    sender.trySendTo(receiver.localPort(), new byte[0]));
            var empty = poll(receiver, 4);
            assertEquals(0, empty.bytes().length);
            assertEquals(sender.localPort(), empty.sourcePort());
            assertEquals(UdpDatagramKernel.SendOutcome.SENT,
                    sender.trySendTo(receiver.localPort(), new byte[] {7}));
            assertArrayEquals(new byte[] {7}, poll(receiver, 4).bytes());
        }
    }

    @Test void truncatedDatagramIsDiscardedAndNextPacketRemainsIndependent() throws Exception {
        try (var sender = bind(); var receiver = bind()) {
            sender.trySendTo(receiver.localPort(), new byte[12]);
            boolean truncated = false;
            for (int i = 0; i < 1000; i++) {
                try {
                    if (receiver.tryReceiveFrom(4) != null) fail("truncated UDP accepted");
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains("truncated"));
                    truncated = true;
                    break;
                }
                Thread.sleep(1);
            }
            assertTrue(truncated, "oversized packet must not become valid payload");
            sender.trySendTo(receiver.localPort(), new byte[] {42});
            assertArrayEquals(new byte[] {42}, poll(receiver, 4).bytes());
        }
    }

    @Test void rejectsIllegalPortsCapAndClosedHandles() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
                UdpDatagramKernel.bindLoopback(-1, (r, op) -> {}));
        assertThrows(IllegalArgumentException.class, () ->
                UdpDatagramKernel.bindLoopback(65536, (r, op) -> {}));
        try (var socket = bind()) {
            assertThrows(IllegalArgumentException.class, () ->
                    socket.trySendTo(0, new byte[]{1}));
            assertThrows(IllegalArgumentException.class, () ->
                    socket.trySendTo(1, new byte[65508]));
            assertThrows(IllegalArgumentException.class, () ->
                    socket.tryReceiveFrom(0));
            socket.close();
            socket.close();
            assertThrows(IOException.class, () -> socket.tryReceiveFrom(1));
            assertThrows(IOException.class, () -> socket.trySendTo(1, new byte[]{1}));
        }
    }

    @Test void authorityIsCheckedBeforeBindAndRecheckedOnSendAndReceive()
            throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(false);
        UdpDatagramKernel.PermissionGate gate = (resource, operation) -> {
            if (!allowed.get()) throw new SecurityException("UDP denied");
        };
        assertThrows(SecurityException.class, () ->
                UdpDatagramKernel.bindLoopback(0, gate));
        allowed.set(true);
        try (var socket = UdpDatagramKernel.bindLoopback(0, gate)) {
            allowed.set(false);
            assertThrows(SecurityException.class, () ->
                    socket.trySendTo(9, new byte[]{1}));
            assertThrows(SecurityException.class, () ->
                    socket.tryReceiveFrom(1));
        }
    }

    @Test void narrowedEndpointGrantCannotBeReusedForAnotherPeer() throws Exception {
        try (var sender = bind(); var receiver = bind()) {
            int permitted = receiver.localPort();
            AtomicBoolean grant = new AtomicBoolean(true);
            try (var constrained = UdpDatagramKernel.bindLoopback(0,
                    (endpoint, operation) -> {
                        if (!grant.get()
                                || (operation.equals("udp.send_to")
                                        && !endpoint.equals("127.0.0.1:" + permitted)))
                            throw new SecurityException("destination denied");
                    })) {
                assertThrows(SecurityException.class, () ->
                        constrained.trySendTo(sender.localPort(), new byte[]{9}));
                assertEquals(UdpDatagramKernel.SendOutcome.SENT,
                        constrained.trySendTo(permitted, new byte[]{9}));
                assertArrayEquals(new byte[]{9}, poll(receiver, 1).bytes());
            }
        }
    }

    @Test void nioReadinessCancellationRearmsWithoutClosingUdpSocket() throws Exception {
        NioReadinessGroup group = new NioReadinessGroup(4, 8);
        try (var sender = bind(); var receiver = bind()) {
            var pending = group.watch(receiver.channel(), java.nio.channels.SelectionKey.OP_READ);
            assertTrue(pending.cancel(false), "one pending receive readiness cancellation");
            assertTrue(group.awaitIdle(5, java.util.concurrent.TimeUnit.SECONDS));
            var ready = group.watch(receiver.channel(), java.nio.channels.SelectionKey.OP_READ);
            assertEquals(UdpDatagramKernel.SendOutcome.SENT,
                    sender.trySendTo(receiver.localPort(), new byte[] {99}));
            int mask = ready.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(0, mask & java.nio.channels.SelectionKey.OP_READ);
            assertArrayEquals(new byte[]{99}, poll(receiver, 1).bytes());
            assertEquals(0, group.pendingCount());
        } finally {
            group.close();
            assertTrue(group.awaitStopped(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

}
