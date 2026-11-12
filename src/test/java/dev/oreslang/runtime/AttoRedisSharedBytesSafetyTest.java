package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * No naked pointers: SHARED actor transport freezes the container graph, but
 * retains runtime-proven immutable Bytes payload identities. PRIVATE domains
 * explicitly take physically disjoint copies.
 *
 * This test does not claim a SharedSlice lease or native zero-copy transport.
 */
final class AttoRedisSharedBytesSafetyTest {
    @Test
    void immutableBytesAreReusedWhileContainerIsDetached() {
        byte[] untrusted = new byte[] {0, (byte) 0xff, 13, 10};
        OresBytes bytes = OresBytes.copyOf(untrusted);
        ArrayList<Object> mutableEnvelope = new ArrayList<>();
        mutableEnvelope.add(bytes);
        @SuppressWarnings("unchecked")
        List<Object> frozen = (List<Object>) ActorRuntime.freeze(mutableEnvelope);
        assertNotSame(mutableEnvelope, frozen,
                "only frozen container metadata may be shared across actors");
        assertSame(bytes, frozen.get(0),
                "shared readonly OresBytes payload need not be copied per actor mail");
        assertThrows(UnsupportedOperationException.class,
                () -> frozen.add(OresBytes.empty()),
                "message envelope is immutable after admission");
        mutableEnvelope.clear();
        untrusted[0] = 99;
        assertEquals(1, frozen.size());
        assertArrayEquals(new byte[] {0, (byte) 0xff, 13, 10},
                bytes.copyToArray(), "input buffer cannot mutate frozen bytes");
        byte[] extracted = bytes.copyToArray();
        extracted[0] = 77;
        assertEquals(0, bytes.unsignedAt(0),
                "outgoing buffer copies must never expose actor's immutable backing");
    }

    @Test
    void privateIsolationCopiesBytesAndMutableDirectBuffersCannotEscape() {
        OresBytes immutable = OresBytes.copyOf(new byte[] {0, 1, 2, (byte) 0xfe});
        OresBytes privateCopy = immutable.isolateCopy();
        assertNotSame(immutable, privateCopy);
        assertEquals(immutable, privateCopy);
        assertArrayEquals(immutable.copyToArray(), privateCopy.copyToArray());
        ByteBuffer pointerLike = ByteBuffer.allocateDirect(4);
        pointerLike.putInt(0x12345678);
        assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(pointerLike),
                "raw mutable direct memory must not cross an actor mailbox");
    }
}
