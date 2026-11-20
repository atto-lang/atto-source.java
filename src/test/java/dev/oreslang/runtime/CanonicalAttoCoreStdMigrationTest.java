package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class CanonicalAttoCoreStdMigrationTest {
    @TempDir Path temp;

    @Test
    void migratedAttoSourcesKeepOneIdentityAndNoLegacyPhysicalDuplicate() throws Exception {
        assertEquals("numeric.atto", CoreLibrarySources.oresCoreFileName("ores.core/numeric"));
        assertEquals(CoreLibrarySources.oresCoreUnitId("ores.core/numeric.atto"),
                CoreLibrarySources.oresCoreUnitId("ores.core/numeric.ores"));
        assertEquals("@ores.core/numeric.ores",
                CoreLibrarySources.oresCoreUnitId("ores.core/numeric.atto"));
        assertEquals(Files.readString(Path.of("src/main/resources/dev/oreslang/core/numeric.atto")),
                CoreLibrarySources.oresCoreSource("ores.core/numeric.ores"));
        assertFalse(Files.exists(Path.of("src/main/resources/dev/oreslang/core/numeric.ores")));
        for (String stem : new String[]{"core", "assert"}) {
            assertEquals(stem + ".atto", CoreLibrarySources.fileName("std/" + stem));
            assertEquals(CoreLibrarySources.unitId("std/" + stem + ".atto"),
                    CoreLibrarySources.unitId("ores.std/" + stem + ".ores"));
            assertEquals(Files.readString(Path.of("stdlib", stem + ".atto")),
                    CoreLibrarySources.source("std/" + stem + ".ores"));
            assertFalse(Files.exists(Path.of("stdlib", stem + ".ores")));
        }
        assertEquals("@std/core.ores", CoreLibrarySources.unitId("std/core.atto"));
        assertEquals("@std/assert.ores", CoreLibrarySources.unitId("std/assert.atto"));
    }

    @Test
    void attoGuestIsCompiledLinkedAndExecutedFromActualSource() throws Exception {
        Path entry = Path.of("src/test/resources/stdlib-parity/migrated-core-assert.atto");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), out, err);
        assertTrue(build.units().containsKey("@std/core.ores"));
        assertTrue(build.units().containsKey("@std/assert.ores"));
        assertTrue(build.units().containsKey("@ores.core/numeric.ores"));
        assertEquals("3\n10\ntrue\ntrue\n12",
                out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void legacyImportsResolveToAttoAndIgnoreFilesystemShadow() throws Exception {
        Path fake = Files.createDirectories(temp.resolve("std"));
        Files.writeString(fake.resolve("core.atto"),
                "define module core as pub fnc min_int(int a, int b): int { return 999; } end");
        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, """
                import module core from "std/core.ores";
                import module numeric from "ores.core/numeric.ores";
                pub routine main(): void {
                  stdio.println(core.min_int(9, 4));
                  stdio.println(numeric.max_int(9, 4));
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), out, new ByteArrayOutputStream());
        assertTrue(build.units().containsKey("@std/core.ores"));
        assertEquals("4\n9", out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void invalidAndUnapprovedSuffixesFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/unknown.atto"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/bounds.ores"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/core.java"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreSource("ores.core/numeric.atto.ores"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.unitId("std/../core.atto"));
    }
}
