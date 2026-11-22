package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AllocSourceLibraryTest {
    @TempDir Path project;

    @Test
    void allocRegistryHasDistinctIdentityAndFailClosedPaths() throws Exception {
        assertEquals(Set.of("stats.atto", "aligned.atto", "secure.atto"),
                CoreLibrarySources.packagedAllocPaths());
        for (String stem : List.of("stats", "aligned", "secure")) {
            String path = "atto.alloc/" + stem;
            assertTrue(CoreLibrarySources.isAllocImport(path));
            assertTrue(CoreLibrarySources.isReservedOresLibraryImport(path));
            assertEquals("@atto.alloc/" + stem + ".atto",
                    CoreLibrarySources.allocUnitId(path));
            assertEquals(CoreLibrarySources.allocUnitId(path),
                    CoreLibrarySources.allocUnitId("alloc/" + stem + ".atto"));
            String text = CoreLibrarySources.allocSource(path);
            assertEquals(text, CoreLibrarySources.allocSource("alloc/" + stem));
            MixedSourceUnit unit = MixedSourceUnit.parse(
                    CoreLibrarySources.allocUnitId(path),
                    CoreLibrarySources.allocFileName(path), text);
            assertTrue(unit.hasOresSource());
            assertFalse(unit.hasJavaSource());
            assertTrue(dev.oreslang.parser.Parser.parse(unit.oresSource()).imports().isEmpty());
        }
        for (String invalid : List.of(
                "atto.alloc/../secure", "atto.alloc//secure",
                "atto.alloc/secure.ores", "atto.alloc/secure/..",
                "atto.alloc/missing", "alloc/.hidden", "alloc/secure\\traversal")) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.allocSource(invalid), invalid);
        }
        assertFalse(CoreLibrarySources.isStdImport("atto.alloc/stats"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/alloc/stats"));
    }

    @Test
    void compilerOwnedAlignmentImportResistsFilesystemShadow() throws Exception {
        Path shadow = Files.createDirectories(project.resolve("atto.alloc"));
        Files.writeString(shadow.resolve("aligned.atto"),
                "define module aligned as pub fnc align_up(int a,int b):int {return 999;} end");
        Path entry = project.resolve("main.atto");
        Files.writeString(entry, """
                import module aligned from "atto.alloc/aligned";
                pub routine main(): void {
                  stdio.println(aligned.align_up(13, 8));
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);
        assertTrue(result.units().containsKey("@atto.alloc/aligned.atto"));
        assertEquals("16", out.toString(StandardCharsets.UTF_8).trim(),
                () -> "unexpected runtime output: " + err.toString(StandardCharsets.UTF_8));
    }
}
