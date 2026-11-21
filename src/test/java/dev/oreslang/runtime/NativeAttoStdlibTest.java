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

final class NativeAttoStdlibTest {
    @TempDir Path temp;

    @Test
    void canonicalAttoResourceIsClosedAndNotAJavaIsland() throws Exception {
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("bounds.atto"));
        assertEquals("@std/bounds.atto", CoreLibrarySources.unitId("std/bounds"));
        assertEquals(CoreLibrarySources.unitId("std/bounds"),
                CoreLibrarySources.unitId("ores.std/bounds.atto"));
        assertEquals("bounds.atto", CoreLibrarySources.fileName("std/bounds"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/bounds.ores"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/bounds.java"));
        String source = CoreLibrarySources.source("std/bounds");
        var parsed = MixedSourceUnit.parse(CoreLibrarySources.unitId("std/bounds"),
                CoreLibrarySources.fileName("std/bounds"), source);
        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
        assertFalse(source.contains("java:"));
    }

    @Test
    void compiledGuestExecutesCanonicalAttoBoundaryMatrix() throws Exception {
        Path fixture = Path.of("src/test/resources/stdlib-parity/bounds.atto");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(fixture,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(), output, errors);
        assertTrue(linked.units().containsKey("@std/bounds.atto"),
                "AttoLang source must be compiled and linked, not run as Java policy");
        assertEquals(String.join("\n", "true", "false", "true", "true", "false",
                "10", "0", "true", "true", "false"),
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void compilerOwnedAttoModuleCannotBeShadowedByProjectSource() throws Exception {
        Path fake = Files.createDirectories(temp.resolve("std"));
        Files.writeString(fake.resolve("bounds.atto"),
                "define module bounds as pub fnc contains_int(int v, int l, int h): bool { return false; } end");
        Path entry = temp.resolve("run.atto");
        Files.writeString(entry, """
                import module bounds from "std/bounds";
                pub routine main(): void {
                  stdio.println(bounds.contains_int(5, 0, 9));
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), output, new ByteArrayOutputStream());
        assertTrue(linked.units().containsKey("@std/bounds.atto"));
        assertEquals("true", output.toString(StandardCharsets.UTF_8).trim());
    }
}
