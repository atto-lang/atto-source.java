package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ManagedReferencePolicyTest {
    @Test
    void denseIndexEncodingUsesAllNonzero32BitPatterns() {
        assertEquals(Integer.BYTES, ManagedReferencePolicy.denseIndexStorageBytes());

        int zero = ManagedReferencePolicy.encodeDenseIndex32(0);
        int fortyOne = ManagedReferencePolicy.encodeDenseIndex32(41);
        int maximum = ManagedReferencePolicy.encodeDenseIndex32(
                ManagedReferencePolicy.MAX_DENSE_INDEX32);

        assertEquals(1, zero);
        assertEquals(42, fortyOne);
        assertEquals(-1, maximum,
                "unsigned 0xffffffff is a valid encoded storage word");
        assertEquals(0, ManagedReferencePolicy.decodeDenseIndex32(zero).orElseThrow());
        assertEquals(41, ManagedReferencePolicy.decodeDenseIndex32(fortyOne).orElseThrow());
        assertEquals(ManagedReferencePolicy.MAX_DENSE_INDEX32,
                ManagedReferencePolicy.decodeDenseIndex32(maximum).orElseThrow());
        assertTrue(ManagedReferencePolicy.decodeDenseIndex32(0).isEmpty());
        assertTrue(ManagedReferencePolicy.isNullDenseIndex32(0));
        assertFalse(ManagedReferencePolicy.isNullDenseIndex32(maximum));

        assertThrows(IllegalArgumentException.class,
                () -> ManagedReferencePolicy.encodeDenseIndex32(-1));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedReferencePolicy.encodeDenseIndex32(0xffff_ffffL));
    }

    @Test
    void jvmHostObjectWidthRemainsVmManaged() {
        assertEquals(NativeBuildMode.ManagedReferenceMode.JVM_MANAGED,
                ManagedReferencePolicy.hostManagedReferenceMode());
    }
}
