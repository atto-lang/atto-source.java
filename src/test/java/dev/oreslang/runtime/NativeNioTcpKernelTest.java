package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class NativeNioTcpKernelTest {
    @Test void selectorNonblockingTcpRoundTrip() throws Exception {
        try (NativeNioTcpKernel kernel = NativeNioTcpKernel.loopback(0, 32)) {
            assertNull(kernel.tryAccept(), "nonblocking accept must return immediately");
            assertTrue(kernel.localPort() > 0);
            try (Socket peer = new Socket(InetAddress.getLoopbackAddress(), kernel.localPort())) {
                peer.setSoTimeout(2000);
                long[] tokens = new long[32];
                int[] ops = new int[32];
                assertTrue(kernel.poll(tokens, ops, 2000) > 0);
                assertEquals(NativeNioTcpKernel.ACCEPT_TOKEN, tokens[0]);
                assertTrue((ops[0] & SelectionKey.OP_ACCEPT) != 0);
                try (SocketChannel incoming = kernel.tryAccept()) {
                    assertNotNull(incoming);
                    assertFalse(incoming.isBlocking());
                    assertEquals(0, kernel.tryRead(incoming, ByteBuffer.allocate(8)));
                    kernel.registerRead(incoming, 123L);
                    peer.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
                    int ready = kernel.poll(tokens, ops, 2000);
                    assertTrue(ready > 0);
                    boolean readable = false;
                    for (int i = 0; i < ready; i++) {
                        if (tokens[i] == 123L && (ops[i] & SelectionKey.OP_READ) != 0) readable = true;
                    }
                    assertTrue(readable);
                    ByteBuffer received = ByteBuffer.allocate(8);
                    assertEquals(5, kernel.tryRead(incoming, received));
                    received.flip();
                    assertEquals("hello", StandardCharsets.UTF_8.decode(received).toString());
                    assertEquals(2, kernel.tryWrite(incoming, ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8))));
                    InputStream stream = peer.getInputStream();
                    assertEquals('o', stream.read());
                    assertEquals('k', stream.read());
                }
            }
        }
    }

    @Test void blockingSocketCannotEnterNonblockingBackend() throws Exception {
        try (NativeNioTcpKernel kernel = NativeNioTcpKernel.loopback(0, 2);
             SocketChannel blocking = SocketChannel.open()) {
            assertTrue(blocking.isBlocking());
            assertThrows(IllegalArgumentException.class, () -> kernel.registerRead(blocking, 2));
            assertThrows(IllegalArgumentException.class,
                () -> kernel.tryRead(blocking, ByteBuffer.allocate(8)));
            assertThrows(IllegalArgumentException.class,
                () -> kernel.tryWrite(blocking, ByteBuffer.wrap(new byte[]{1})));
            assertThrows(IllegalArgumentException.class, () -> kernel.poll(new long[0], new int[0], 0));
            assertThrows(IllegalArgumentException.class, () -> kernel.poll(new long[2], new int[1], 0));
        }
    }
}
