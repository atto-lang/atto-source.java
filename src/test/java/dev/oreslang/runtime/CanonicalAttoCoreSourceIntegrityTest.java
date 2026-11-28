package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiled guest and packaged resource hardening for the canonical .atto migration.
 * Passing source inspection alone is not evidence that any CI guest job executed.
 */
final class CanonicalAttoCoreSourceIntegrityTest {
    @TempDir Path temp;

    @Test
    void canonicalResourceBytesAndCompiledSourceAreExactlyTheSame() throws Exception {
        for (String stem : new String[]{"core", "assert"}) {
            String name = "std/" + stem;
            String source = CoreLibrarySources.source(name);
            MixedSourceUnit unit = MixedSourceUnit.parse(
                    CoreLibrarySources.unitId(name), CoreLibrarySources.fileName(name), source);
            assertTrue(unit.hasOresSource(), name);
            assertFalse(unit.hasJavaSource(), name);
            assertFalse(source.contains("java:"), name);
            assertArrayEquals(Files.readAllBytes(Path.of("stdlib", stem + ".atto")),
                    source.getBytes(StandardCharsets.UTF_8), name);
            assertEquals(source, CoreLibrarySources.source("ores.std/" + stem + ".ores"));
            assertEquals(CoreLibrarySources.unitId("std/" + stem + ".atto"),
                    CoreLibrarySources.unitId("std/" + stem + ".ores"));
        }
        String source = CoreLibrarySources.oresCoreSource("ores.core/numeric");
        MixedSourceUnit unit = MixedSourceUnit.parse(
                CoreLibrarySources.oresCoreUnitId("ores.core/numeric"),
                CoreLibrarySources.oresCoreFileName("ores.core/numeric"), source);
        assertTrue(unit.hasOresSource());
        assertFalse(unit.hasJavaSource());
        assertFalse(source.contains("java:"));
        assertArrayEquals(Files.readAllBytes(
                Path.of("src/main/resources/dev/oreslang/core/numeric.atto")),
                source.getBytes(StandardCharsets.UTF_8));
        assertEquals(source, CoreLibrarySources.oresCoreSource("ores.core/numeric.ores"));
        assertEquals(CoreLibrarySources.oresCoreUnitId("ores.core/numeric.atto"),
                CoreLibrarySources.oresCoreUnitId("ores.core/numeric.ores"));
    }

    @Test
    void importingBothExtensionsCompilesEachModuleOnlyOnceAndExecutesGuest() throws Exception {
        Path entry = temp.resolve("alias-reuse.atto");
        Files.writeString(entry, """
                import * as newCore from "std/core.atto";
                import * as oldCore from "std/core.ores";
                import * as newAssert from "ores.std/assert.atto";
                import * as oldAssert from "std/assert.ores";
                import * as newNumeric from "ores.core/numeric.atto";
                import * as oldNumeric from "ores.core/numeric.ores";
                pub routine main(): void {
                  stdio.println(newCore.min_int(9, 4));
                  stdio.println(oldCore.max_int(9, 4));
                  stdio.println(newAssert.equal(3, 3));
                  stdio.println(oldAssert.false(false));
                  stdio.println(newNumeric.max_int(7, 11));
                  stdio.println(oldNumeric.min_int(7, 11));
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), out, err);
        assertEquals("4\n9\ntrue\ntrue\n11\n7",
                out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> err.toString(StandardCharsets.UTF_8));
        for (String id : new String[]{
                "@std/core.ores", "@std/assert.ores", "@ores.core/numeric.ores"}) {
            assertEquals(1L, build.units().keySet().stream().filter(id::equals).count(), id);
        }
    }

    @Test
    void malformedAndUnauthorizedSourceSpellingsFailClosed() throws Exception {
        for (String path : new String[]{
                "std/core.atto.ores", "std/core.ores.atto", "std/assert.atto.atto",
                "std/bounds.ores", "std/core%2eatto", "std//core", "std/../core",
                "ores.std/unknown.atto", "std/core.java"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.source(path), path);
        }
        for (String path : new String[]{
                "ores.core/numeric.atto.ores", "ores.core/numeric.ores.atto",
                "ores.core/../numeric", "ores.core//numeric",
                "ores.core/unknown.atto", "ores.core/numeric%2eatto"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.oresCoreSource(path), path);
        }
        assertEquals(CoreLibrarySources.source("ores.std/core.atto"),
                CoreLibrarySources.source("std/core.ores"));
        assertEquals(CoreLibrarySources.source("ores.std/assert.ores"),
                CoreLibrarySources.source("std/assert.atto"));
    }

    @Test
    void nativeImageResourceMetadataIncludesFreestandingAndHostedAttoSources() throws Exception {
        String metadata = Files.readString(Path.of(
                "src/main/resources/META-INF/native-image/dev.oreslang/oreslang/reachability-metadata.json"));
        assertTrue(metadata.contains("\"glob\": \"dev/oreslang/core/*.atto\""));
        assertTrue(metadata.contains("\"glob\": \"dev/oreslang/core/**/*.atto\""));
        assertTrue(metadata.contains("\"glob\": \"dev/oreslang/std/*.atto\""));
    }
}
