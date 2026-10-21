package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ActorCompressed32ReferenceTest {
    @Test
    void zeroIsNullAndAllNonzeroWordsDecodeUnsigned() {
        assertThrows(SecurityException.class, () -> ActorCompressed32Reference.decode(0));
        assertEquals(1, ActorCompressed32Reference.encode(0));
        assertEquals(0L, ActorCompressed32Reference.decode(1));
        assertEquals(8L, ActorCompressed32Reference.decode(2));
        assertEquals(0x8000_0000, ActorCompressed32Reference.encode(0x7fff_ffffL * 8L));
        assertEquals(0x7fff_ffffL * 8L,
                ActorCompressed32Reference.decode(0x8000_0000));
        assertEquals(-1, ActorCompressed32Reference.encode(
                ActorCompressed32Reference.MAX_OFFSET_BYTES));
        assertEquals(ActorCompressed32Reference.MAX_OFFSET_BYTES,
                ActorCompressed32Reference.decode(-1));
        assertEquals(ActorCompressed32Reference.MAX_SPAN_BYTES,
                ActorCompressed32Reference.MAX_OFFSET_BYTES + 8);
    }

    @Test
    void rejectsMisalignmentOverflowNullAndTamperedHighBits() {
        assertThrows(IllegalArgumentException.class,
                () -> ActorCompressed32Reference.encode(-8));
        assertThrows(IllegalArgumentException.class,
                () -> ActorCompressed32Reference.encode(7));
        assertThrows(IllegalArgumentException.class,
                () -> ActorCompressed32Reference.encode(
                        ActorCompressed32Reference.MAX_OFFSET_BYTES + 8));
        assertThrows(SecurityException.class,
                () -> ActorCompressed32Reference.decodeStored(1L << 32));
        assertThrows(SecurityException.class,
                () -> ActorCompressed32Reference.decodeStored(0));
    }

    @Test
    void checkedDecodeRejectsUncommittedAndCrossBoundaryAccess() {
        assertEquals(8, ActorCompressed32Reference.decodeChecked(2, 16, 8));
        assertThrows(SecurityException.class,
                () -> ActorCompressed32Reference.decodeChecked(2, 15, 8));
        assertThrows(SecurityException.class,
                () -> ActorCompressed32Reference.decodeChecked(2, 16, 0));
        assertThrows(SecurityException.class,
                () -> ActorCompressed32Reference.decodeChecked(2, -1, 1));
        assertThrows(SecurityException.class,
                () -> ActorCompressed32Reference.decodeChecked(2, 16, Long.MAX_VALUE));
        assertEquals(ActorCompressed32Reference.MAX_OFFSET_BYTES,
                ActorCompressed32Reference.decodeChecked(0xffff_ffffL,
                        ActorCompressed32Reference.MAX_SPAN_BYTES, 8));
    }
}
