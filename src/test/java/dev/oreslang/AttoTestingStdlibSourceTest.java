package dev.oreslang;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiler-owned standard-library resource admission, not a substitute for
 * executing an AttoLang .atto guest. The guest workflows provide that proof.
 */
final class AttoTestingStdlibSourceTest {
    @Test
    void extensionlessTestingImportResolvesCanonicalAttoResource() throws Exception {
        assertEquals("@std/testing.atto", CoreLibrarySources.unitId("ores.std/testing"));
        assertEquals("@std/testing.atto", CoreLibrarySources.unitId("std/testing"));
        assertEquals("@std/testing.atto", CoreLibrarySources.unitId("ores.std/testing.atto"));
        assertEquals("testing.atto", CoreLibrarySources.fileName("ores.std/testing"));
        assertEquals("testing.atto", CoreLibrarySources.fileName("std/testing.atto"));
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("testing.atto"));

        String nativeSource = CoreLibrarySources.source("ores.std/testing");
        assertTrue(nativeSource.contains("define module testing as"));
        assertTrue(nativeSource.contains("native AttoLang test suite failed"));
        assertFalse(nativeSource.contains("native Oreslang test suite failed"));
        assertEquals(nativeSource, CoreLibrarySources.source("std/testing.atto"));
    }

    @Test
    void legacyOresSuffixIsExplicitAndDoesNotOverrideCanonicalAtto() throws Exception {
        assertEquals("@std/testing.ores", CoreLibrarySources.unitId("ores.std/testing.ores"));
        assertEquals("testing.ores", CoreLibrarySources.fileName("ores.std/testing.ores"));
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("testing.ores"));

        String legacySource = CoreLibrarySources.source("ores.std/testing.ores");
        assertTrue(legacySource.contains("define module testing as"));
        assertTrue(legacySource.contains("native Oreslang test suite failed"));
        assertFalse(legacySource.contains("native AttoLang test suite failed"));
    }

    @Test
    void rejectsPathTraversalAndForgedCanonicalTestingSuffixes() {
        for (String invalid : new String[] {
                "ores.std/../testing",
                "ores.std/testing/../testing",
                "ores.std/testing.atto/child",
                "ores.std/testing.atto.ores",
                "ores.std/testing..atto",
                "ores.std/testing.exe",
                "ores.std/testing.atto\\malicious",
                "ores.std/testing.atto/../../outside"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.source(invalid), invalid);
        }
    }
}
