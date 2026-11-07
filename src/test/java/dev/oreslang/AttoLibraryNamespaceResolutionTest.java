package dev.oreslang;

import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real Java/Truffle compiler and guest check, not an import-string model. */
final class AttoLibraryNamespaceResolutionTest {
    @TempDir Path temp;

    @Test
    void canonicalAndLegacyNamesShareCompilerOwnedModuleIdentity() throws Exception {
        assertTrue(CoreLibrarySources.isOresCoreImport("atto.core/numeric.atto"));
        assertTrue(CoreLibrarySources.isOresCoreImport("ores.core/numeric.ores"));
        assertEquals(CoreLibrarySources.oresCoreUnitId("atto.core/numeric.atto"),
                CoreLibrarySources.oresCoreUnitId("ores.core/numeric.ores"));
        assertEquals(CoreLibrarySources.oresCoreSource("atto.core/numeric"),
                CoreLibrarySources.oresCoreSource("ores.core/numeric"));
        assertEquals(CoreLibrarySources.unitId("atto.std/testing.atto"),
                CoreLibrarySources.unitId("std/testing.ores"));
        assertEquals(CoreLibrarySources.source("atto.std/testing"),
                CoreLibrarySources.source("ores.std/testing.ores"));
    }

    @Test
    void canonicalAttoCoreFixtureCompilesLinksAndExecutesGuest() throws Exception {
        Path source = temp.resolve("numeric.atto");
        Files.writeString(source, """
                import module numeric from "atto.core/numeric";
                pub fnc main(): void {
                  stdio.stdout.write(numeric.max_int(40, 42));
                  return;
                }
                """, StandardCharsets.UTF_8);
        assertEquals(2, LinkedProgramRunner.validate(source, Map.of()).units().size());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        var program = LinkedProgramRunner.run(source, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), stdout, stderr);
        assertEquals(2, program.units().size());
        assertEquals("42", stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void allCoreAliasSuffixesResolveToOneCompilerUnit() throws Exception {
        for (String alias : new String[] {
                "atto.core/numeric", "atto.core/numeric.atto",
                "atto.core/numeric.ores", "ores.core/numeric",
                "ores.core/numeric.atto", "ores.core/numeric.ores"
        }) {
            assertEquals("@ores.core/numeric.ores", CoreLibrarySources.oresCoreUnitId(alias), alias);
            assertEquals("numeric.atto", CoreLibrarySources.oresCoreFileName(alias), alias);
            assertEquals(CoreLibrarySources.oresCoreSource("atto.core/numeric"),
                    CoreLibrarySources.oresCoreSource(alias), alias);
        }
    }

    @Test
    void canonicalCoreImportRejectsPathConfusionAndUnknownModules() {
        for (String suffix : new String[] {
                "", "../numeric", "./numeric", "//numeric", "numeric/",
                "numeric\\\\evil", "missing", "numeric.txt",
                "numeric.atto/../numeric", "numeric%2fextra"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.oresCoreSource("atto.core/" + suffix), suffix);
        }
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreSource("atto.core/numeric\u0000hidden"));
    }

    @Test
    void executableCanonicalCoreNumericEdgesAreLinkedNotSimulated() throws Exception {
        String[][] cases = {
                {"numeric.min_int(41, 42)", "41"},
                {"numeric.max_int(41, 42)", "42"},
                {"numeric.min_int(-3, -5)", "-5"},
                {"numeric.clamp_int(45, 0, 42)", "42"},
                {"numeric.clamp_int(4, 10, 42)", "10"},
                {"numeric.clamp_int(7, 10, 5)", "10"}
        };
        for (int i = 0; i < cases.length; i++) {
            Path source = temp.resolve("core-numeric-" + i + ".atto");
            Files.writeString(source, """
                    import module numeric from "atto.core/numeric";
                    pub fnc main(): void {
                      stdio.stdout.write(%s);
                      return;
                    }
                    """.formatted(cases[i][0]), StandardCharsets.UTF_8);
            assertEquals(2, LinkedProgramRunner.validate(source, Map.of()).units().size());
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            var linked = LinkedProgramRunner.run(source, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), Set.of(), Map.of(), stdout, stderr);
            assertEquals(2, linked.units().size(), cases[i][0]);
            assertEquals(cases[i][1], stdout.toString(StandardCharsets.UTF_8), cases[i][0]);
        }
    }

    @Test
    void stdCanonicalAliasesDoNotDuplicateTheirHistoricalIds() throws Exception {
        for (String alias : new String[] {
                "atto.std/testing", "atto.std/testing.atto", "atto.std/testing.ores",
                "ores.std/testing", "ores.std/testing.ores", "std/testing"
        }) {
            assertEquals("@std/testing.ores", CoreLibrarySources.unitId(alias), alias);
            assertEquals(CoreLibrarySources.source("std/testing"), CoreLibrarySources.source(alias), alias);
        }
    }

    @Test
    void unsupportedCanonicalRootsCannotFallBackToUserShadowSource() throws Exception {
        for (String namespace : new String[] {"atto.alloc", "atto.runtime.actor"}) {
            Path shadow = temp.resolve(namespace);
            Files.createDirectories(shadow);
            Files.writeString(shadow.resolve("int_buffer.atto"),
                    "define module int_buffer as pub fnc surprise():int{return 99;} end");
            Path program = temp.resolve("deny-" + namespace + ".atto");
            Files.writeString(program, """
                    import module int_buffer from "%s/int_buffer";
                    pub fnc main(): void {
                      stdio.stdout.write(int_buffer.surprise());
                      return;
                    }
                    """.formatted(namespace), StandardCharsets.UTF_8);
            assertTrue(CoreLibrarySources.isReservedOresLibraryImport(namespace + "/int_buffer"));
            assertThrows(Exception.class, () -> LinkedProgramRunner.validate(program, Map.of()),
                    "Compiler must not resolve reserved " + namespace + " from ambient user files");
        }
    }

    @Test
    void unsupportedNamespacesAndTraversalAreNeverAmbientFilesystemImports() {
        for (String path : new String[] {
                "atto.core", "atto.alloc", "atto.alloc/int_buffer",
                "atto.std", "atto.runtime.actor", "atto.runtime.actor/scheduler"
        }) {
            assertTrue(CoreLibrarySources.isReservedOresLibraryImport(path), path);
        }
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreSource("atto.core/missing.atto"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreUnitId("atto.core/../numeric"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreUnitId("atto.core//numeric"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("atto.std/../testing"));
        assertFalse(CoreLibrarySources.isReservedOresLibraryImport("./atto.core/numeric"));
    }
}
