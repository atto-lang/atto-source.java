package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OresBytesBinaryCodecTest {
    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test void detachedOwnershipAndUseAfterTakeAreEnforced() {
        byte[] original = {0, (byte) 255, 1};
        try (OresBytes bytes = OresBytes.copyOf(original)) {
            original[1] = 7;
            assertEquals(255, bytes.byteAt(1), "copyOf must not alias caller buffer");
            OresBytes moved = bytes.take();
            assertThrows(IllegalStateException.class, bytes::size);
            assertThrows(IllegalStateException.class, bytes::take);
            bytes.close(); // stale owner cannot free the moved allocation.
            assertEquals(255, moved.byteAt(1));
            try (moved) {
                assertArrayEquals(new byte[] {0, (byte) 255, 1}, moved.toArrayCopy());
            }
            assertThrows(IllegalStateException.class, moved::size);
        }
    }

    @Test void readLeasePreventsPrematureZeroizationDuringOwnershipTransfer() {
        byte[] detached = {(byte) 192, 0, (byte) 255};
        OresBytes first = OresBytes.fromDetached(detached);
        OresBytes.ReadLease lease = first.readLease();
        OresBytes moved = first.take();
        first.close();
        moved.close();
        // A pending NIO2 write may still read its immutable buffer.
        assertArrayEquals(new byte[] {(byte) 192, 0, (byte) 255},
                new byte[] {lease.buffer().get(), lease.buffer().get(1), lease.buffer().get(2)});
        lease.close();
        assertArrayEquals(new byte[] {0, 0, 0}, detached);
        assertThrows(IllegalStateException.class, lease::buffer);
        lease.close(); // exact-once decrement
    }

    @Test void rejectsOverlargeBuffersAndEscapedMutation() {
        assertThrows(IllegalArgumentException.class,
                () -> OresBytes.copyOf(new byte[OresBytes.MAX_LENGTH + 1]));
        try (OresBytes owned = OresBytes.copyOf(new byte[] {3, 4})) {
            byte[] copy = owned.toArrayCopy();
            copy[0] = 97;
            assertEquals(3, owned.byteAt(0));
        }
    }

    @Test void twoConcurrentTakeAttemptsCannotDuplicateOwnership() throws Exception {
        OresBytes source = OresBytes.copyOf(new byte[] {11, 12});
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger(), losses = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<OresBytes> child =
                new java.util.concurrent.atomic.AtomicReference<>();
        Runnable transfer = () -> {
            ready.countDown();
            try {
                start.await();
                OresBytes moved = source.take();
                child.set(moved);
                wins.incrementAndGet();
            } catch (IllegalStateException rejected) {
                losses.incrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        Thread a = new Thread(transfer), b = new Thread(transfer);
        a.start(); b.start(); ready.await(); start.countDown(); a.join(); b.join();
        assertEquals(1, wins.get());
        assertEquals(1, losses.get());
        assertNotNull(child.get());
        child.get().close();
    }

    @Test void binaryResp2FramesPreserveAllOctetsWithoutHexString() throws Exception {
        byte[] frame = {'*','3','\r','\n','$','3','\r','\n','S','E','T','\r','\n',
            '$','3','\r','\n',0,(byte) 255,'\n','\r','\n',
            '$','2','\r','\n',(byte) 128,(byte) 254,'\r','\n'};
        ByteBuffer input = ByteBuffer.wrap(frame);
        AsyncNioResp2.BinaryParsed decoded = AsyncNioResp2.parseBinary(input);
        assertEquals("SET", decoded.opcode());
        assertEquals(2, decoded.bulk().size());
        assertEquals(frame.length, decoded.consumed());
        assertEquals(0, input.position(), "decoder must not consume on caller's behalf");
        try (OresBytes key = decoded.bulk().get(0); OresBytes value = decoded.bulk().get(1)) {
            assertArrayEquals(new byte[] {0, (byte) 255, '\n'}, key.toArrayCopy());
            assertArrayEquals(new byte[] {(byte) 128, (byte) 254}, value.toArrayCopy());
            assertArrayEquals(
                    new byte[] {36, 50, 13, 10, (byte) 128, (byte) 254, 13, 10},
                    AsyncNioResp2.encodeBinary("VALUE", value));
            assertEquals(AsyncNioResp2.encode("VALUE", "80fe").length,
                    AsyncNioResp2.encodeBinary("VALUE", value).length);
        }
        assertThrows(IllegalStateException.class,
                () -> AsyncNioResp2.encodeBinary("VALUE", decoded.bulk().get(0)));
    }

    @Test void fragmentedBinaryAndMalformedHeadersStayFailClosed() throws Exception {
        byte[] frame = ascii("*2\r\n$3\r\nGET\r\n$1\r\nx\r\n");
        for (int n = 0; n < frame.length; n++) {
            assertNull(AsyncNioResp2.parseBinary(ByteBuffer.wrap(frame, 0, n)));
        }
        assertThrows(IOException.class,
                () -> AsyncNioResp2.parseBinary(ByteBuffer.wrap(ascii("*9\r\n"))));
        assertThrows(IOException.class,
                () -> AsyncNioResp2.parseBinary(ByteBuffer.wrap(ascii(
                        "*2\r\n$3\r\nGET\r\n$4097\r\n"))));
    }

    @Test void binaryEncoderKeepsMissingEmptyAndValueDistinct() throws Exception {
        try (OresBytes empty = OresBytes.copyOf(new byte[0])) {
            assertArrayEquals(ascii("$-1\r\n"), AsyncNioResp2.encodeBinary("NIL", null));
            assertArrayEquals(ascii("$0\r\n\r\n"), AsyncNioResp2.encodeBinary("VALUE", empty));
        }
        assertThrows(IOException.class, () -> AsyncNioResp2.encodeBinary("VALUE", null));
    }
}
