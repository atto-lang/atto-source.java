package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class CanonicalAttoStdFiveMoreTest {
    private static final Set<String> MIGRATED = Set.of(
            "compute", "trace", "db_pool", "http_wire", "actor_persistence");
    @TempDir Path temp;

    @Test
    void fiveCanonicalModulesUseOneSourceAndPreserveLegacyUnitIds() throws Exception {
        for (String stem : MIGRATED) {
            String canonical = "std/" + stem + ".atto";
            String legacy = "ores.std/" + stem + ".ores";
            assertTrue(CoreLibrarySources.packagedSourcePaths().contains(stem + ".atto"));
            assertFalse(CoreLibrarySources.packagedSourcePaths().contains(stem + ".ores"));
            assertFalse(Files.exists(Path.of("stdlib", stem + ".ores")));
            assertEquals(stem + ".atto", CoreLibrarySources.fileName("std/" + stem));
            assertEquals("@std/" + stem + ".ores", CoreLibrarySources.unitId(canonical));
            assertEquals(CoreLibrarySources.unitId(canonical),
                    CoreLibrarySources.unitId(legacy));
            assertEquals(CoreLibrarySources.unitId(canonical),
                    CoreLibrarySources.unitId("std/" + stem));
            String source = Files.readString(Path.of("stdlib", stem + ".atto"));
            assertEquals(source, CoreLibrarySources.source(canonical));
            assertEquals(source, CoreLibrarySources.source(legacy));
            var guest = MixedSourceUnit.parse(
                    CoreLibrarySources.unitId(canonical),
                    CoreLibrarySources.fileName(canonical), source);
            assertTrue(guest.hasOresSource());
            assertFalse(guest.hasJavaSource(), "no Java source islands: " + stem);
            assertFalse(source.contains("java:"), "no ambient host imports: " + stem);
        }
    }

    private void expectGuest(String stem, String expected) throws Exception {
        Path fixture = Path.of("src/test/resources/stdlib-parity/five-more",
                stem + ".atto");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(fixture,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);
        assertTrue(linked.units().containsKey("@std/" + stem + ".ores"),
                () -> "missing compiler-owned unit: " + stem);
        assertEquals(expected,
                out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> "stdlib " + stem + ": " + err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void computeAttoCompilesAndLinksWithoutClaimingDeviceExecution() throws Exception {
        expectGuest("compute", "42");
    }

    @Test
    void traceAttoRunsSourceNativeEventLogic() throws Exception {
        expectGuest("trace", "0\n5");
    }

    @Test
    void dbPoolAttoRunsBoundedAdmissionAndRelease() throws Exception {
        expectGuest("db_pool", "true\nfalse\ntrue\n0");
    }

    @Test
    void httpWireAttoRunsStrictHeaderParser() throws Exception {
        expectGuest("http_wire", "true\n/ok\ntrue");
    }

    @Test
    void actorPersistenceAttoRejectsStaleReceipt() throws Exception {
        expectGuest("actor_persistence", "5\n5\nfalse\ntrue\n5");
    }

    @Test
    void legacyImportCannotBeShadowedByLocalAttoFile() throws Exception {
        Path shadow = Files.createDirectories(temp.resolve("std"));
        Files.writeString(shadow.resolve("trace.atto"),
                "define module Trace as pub fnc start_ns(bool e): int { return 999; } end");
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, """
                import module Trace from "std/trace.ores";
                pub routine main(): void {
                  stdio.println(Trace.start_ns(false));
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(entry,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                out, new ByteArrayOutputStream());
        assertTrue(linked.units().containsKey("@std/trace.ores"));
        assertEquals("0", out.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void unknownAndUnmigratedAttoModulesRemainFailClosed() {
        for (String unknown : new String[]{
                "std/missing.atto", "std/testing.atto", "std/data_pool.atto",
                "std/trace.atto.ores", "std/http_wire.java",
                "std/../actor_persistence.atto"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.source(unknown), unknown);
        }
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/bounds.ores"));
    }
}
