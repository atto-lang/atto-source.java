package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.stdlib.CoreLibrarySources;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real imported Atto alloc source, safety checks and guest execution. */
final class AttoAllocGuestTest {
    @TempDir Path temp;

    @Test
    void closedAllocSourcesResolveToOneCompilerOwnedUnitPerModule() throws Exception {
        assertEquals(6, CoreLibrarySources.packagedAttoAllocPaths().size());
        for (String name : new String[]{"allocator", "set", "drop", "arc", "pool", "weak"}) {
            String canonical = "atto.alloc/" + name;
            String source = CoreLibrarySources.attoAllocSource(canonical);
            assertTrue(source.contains("use namespace atto_alloc_" + name + ";"), canonical);
            var parsed = Parser.parse(source);
            assertEquals("atto_alloc_" + name, parsed.namespace(), canonical);
            assertTrue(parsed.modules().stream().anyMatch(module ->
                    module.declarations().stream().anyMatch(declaration ->
                            declaration instanceof dev.oreslang.ast.Ast.FunctionDecl function
                                    && function.name().equals("self_test"))), canonical);
            assertEquals(source, CoreLibrarySources.attoAllocSource(canonical + ".atto"));
            assertEquals(CoreLibrarySources.attoAllocUnitId(canonical),
                    CoreLibrarySources.attoAllocUnitId(canonical + ".atto"));
            assertEquals(name + ".atto", CoreLibrarySources.attoAllocFileName(canonical));
            assertTrue(CoreLibrarySources.isReservedOresLibraryImport(canonical));
            assertDoesNotThrow(() -> Parser.parse(source), canonical);
        }
    }

    @Test
    void rejectTraversalUnlistedSuffixesAndNamespaceShadowing() throws Exception {
        for (String invalid : new String[]{
                "atto.alloc/../set",
                "atto.alloc/./set",
                "atto.alloc/set/../arc",
                "atto.alloc/set//arc",
                "atto.alloc/set.ores",
                "atto.alloc/unknown",
                "atto.alloc/set.atto/anything",
                "atto.alloc/%2e%2e/set",
                "atto.alloc\\set",
                "atto.alloc/",
                "atto.alloc/set" + (char) 0
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.attoAllocSource(invalid), invalid);
        }
        assertTrue(CoreLibrarySources.isReservedOresLibraryImport("atto.alloc"));
        assertTrue(CoreLibrarySources.isReservedOresLibraryImport("atto.alloc/unknown"));
        Path forged = temp.resolve("shadow.atto");
        Files.writeString(forged, """
                import fnc self_test from "atto.alloc/unknown";
                pub fnc main(): int { return self_test() ? 42 : 1; }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(forged, Map.of()));
    }

    @Test
    void importedAttoAllocSelfTestsExecuteAsGuestPrograms() throws Exception {
        for (String module : new String[]{"allocator", "set", "drop", "arc", "pool", "weak"}) {
            Path guest = Path.of("tests", "fixtures", "alloc-java-guest",
                    module + "-positive.atto");
            assertTrue(Files.isRegularFile(guest), "missing native .atto fixture " + guest);
            assertDoesNotThrow(() -> LinkedProgramRunner.validate(guest, Map.of()), module);
            var result = LinkedProgramRunner.runWithExitStatus(
                    guest, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    new ByteArrayOutputStream(), new ByteArrayOutputStream());
            assertEquals(42, result.exitStatus(),
                    "must execute compiled Atto policy code: " + module);
        }
    }

    @Test
    void importedAllocClassesExecuteRealGuestTransactions() throws Exception {
        // These are linked guest programs consuming public Atto classes,
        // not Java-only models or parser-only fixture acceptance.
        for (String name : new String[]{
                "allocator-transactions-positive.atto",
                "set-hash-operations-positive.atto"}) {
            Path guest = Path.of("tests", "fixtures", "alloc-java-guest", name);
            assertTrue(Files.isRegularFile(guest), "missing Atto fixture " + name);
            assertDoesNotThrow(() -> LinkedProgramRunner.validate(guest, Map.of()), name);
            var result = LinkedProgramRunner.runWithExitStatus(
                    guest, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    new ByteArrayOutputStream(), new ByteArrayOutputStream());
            assertEquals(42, result.exitStatus(), "real guest must run " + name);
        }
    }

    @Test
    void missingOrForgedAllocExportsRejectAtLink() throws Exception {
        Path bad = Path.of("tests", "fixtures", "alloc-java-guest",
                "missing-export-negative.atto");
        assertTrue(Files.isRegularFile(bad));
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(bad, Map.of()),
                "only real declarations may be imported from closed alloc units");
    }
    @Test
    void namespacesMustRemainFlatAndFileScoped() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main(): void {
                  use namespace invalid;
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                use namespace alloc.bad;
                pub fnc main(): void { return; }
                """));
    }

    @Test
    void reservationLedgerPublicFunctionMustExistInRealGuestSource() throws Exception {
        var source = CoreLibrarySources.attoAllocSource("atto.alloc/allocator");
        var parsed = Parser.parse(source);
        assertEquals("atto_alloc_allocator", parsed.namespace());
        assertTrue(parsed.modules().stream().anyMatch(module ->
                module.declarations().stream().anyMatch(declaration ->
                        declaration instanceof dev.oreslang.ast.Ast.FunctionDecl function
                                && function.name().equals("reservation_self_test"))));
    }

    @Test
    void unsafeNominalAllocCapabilitiesMustNotBeForgedByImport() throws Exception {
        for (String name : new String[]{
                "arc-shared-owner-negative.atto",
                "set-generic-hash-negative.atto",
                "allocator-physical-handle-negative.atto"}) {
            Path fixture = Path.of("tests", "fixtures", "alloc-java-guest", name);
            assertTrue(Files.isRegularFile(fixture), "missing negative .atto: " + name);
            assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(fixture, Map.of()), name);
        }
    }

}
