package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class BuildProfileTest {
    @Test
    void strictAotAndHardRtRequireFullAot() {
        assertTrue(BuildProfile.STRICT_AOT.requiresFullAot());
        assertTrue(BuildProfile.HARD_RT.requiresFullAot());
        assertFalse(BuildProfile.RELEASE.requiresFullAot());
    }

    @Test
    void releaseOrStrongerRequiresTreeShaking() {
        assertFalse(BuildProfile.DEV.requiresTreeShaking());
        assertTrue(BuildProfile.RELEASE.requiresTreeShaking());
        assertTrue(BuildProfile.STRICT_AOT.requiresTreeShaking());
        assertTrue(BuildProfile.HARD_RT.requiresTreeShaking());
    }

    @Test
    void selectorsAreMutuallyExclusive() {
        assertEquals(
                BuildProfile.STRICT_AOT,
                BuildProfile.select(BuildProfile.DEV, BuildProfile.STRICT_AOT));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> BuildProfile.select(BuildProfile.RELEASE, BuildProfile.HARD_RT));
        assertTrue(error.getMessage().contains("mutually exclusive"));
    }
}
