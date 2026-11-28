package dev.oreslang.runtime;

import java.util.AbstractList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Compiler-backend JUnit tests of the exact payload snapshot permission gate. */
final class UdpSendAuthorityBoundaryTest {
    private static final class PoisonBytes extends AbstractList<Number> {
        int reads;
        @Override public int size() {
            reads++;
            throw new AssertionError("guest length read before authorization");
        }
        @Override public Number get(int index) {
            reads++;
            throw new AssertionError("guest item read before authorization");
        }
    }

    @Test void deniedDestinationNeverReadsGuestListOrAllocatesPayload() {
        PoisonBytes bytes = new PoisonBytes();
        AtomicInteger checks = new AtomicInteger();
        assertThrows(SecurityException.class, () ->
                AsyncNioUdp.Socket.snapshotAuthorizedOctets(9000, bytes,
                    (address, operation) -> {
                        checks.incrementAndGet();
                        assertEquals("127.0.0.1:9000", address);
                        assertEquals("udp.send_to", operation);
                        throw new SecurityException("NET denied");
                    }));
        assertEquals(1, checks.get());
        assertEquals(0, bytes.reads, "no guest storage may be inspected");
    }

    @Test void malformedDestinationIsDeniedBeforeGateAndGuestStorage() {
        PoisonBytes bytes = new PoisonBytes();
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () ->
                AsyncNioUdp.Socket.snapshotAuthorizedOctets(0, bytes,
                        (address, operation) -> checks.incrementAndGet()));
        assertEquals(0, checks.get());
        assertEquals(0, bytes.reads);
    }

    @Test void authorizedPayloadUsesIndependentBoundedOwnedByteSnapshot() {
        Number[] values = {0, 42, 255};
        byte[] detached = AsyncNioUdp.Socket.snapshotAuthorizedOctets(65535,
                java.util.Arrays.asList(values), (address, operation) -> {});
        assertArrayEquals(new byte[] {0, 42, (byte) 255}, detached);
        values[1] = 99;
        assertEquals(42, Byte.toUnsignedInt(detached[1]));
        assertThrows(IllegalArgumentException.class,
                () -> AsyncNioUdp.Socket.snapshotAuthorizedOctets(10,
                        List.of(256), (address, operation) -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> AsyncNioUdp.Socket.snapshotAuthorizedOctets(10,
                        List.of(1.5), (address, operation) -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> AsyncNioUdp.Socket.snapshotAuthorizedOctets(10,
                        List.of(-1), (address, operation) -> {}));
    }

    @Test void oversizedListRefusedWithoutReadingItsContents() {
        AtomicInteger readCount = new AtomicInteger();
        List<Number> oversized = new AbstractList<>() {
            @Override public int size() { return 65508; }
            @Override public Number get(int i) {
                readCount.incrementAndGet();
                throw new AssertionError("oversized guest list element read");
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> AsyncNioUdp.Socket.snapshotAuthorizedOctets(1234, oversized,
                        (address, operation) -> {}));
        assertEquals(0, readCount.get());
    }
}
