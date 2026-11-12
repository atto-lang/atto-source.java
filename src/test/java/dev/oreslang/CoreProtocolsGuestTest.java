package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Real compiler-owned core import and declaration admission for v1 protocols. */
final class CoreProtocolsGuestTest {
    @TempDir Path temp;

    @Test
    void protocolSourceIsRegisteredAndTypechecked() throws Exception {
        assertTrue(CoreLibrarySources.packagedCorePaths().contains("protocols.atto"));
        assertFalse(CoreLibrarySources.packagedCorePaths().contains("protocols.ores"));
        assertEquals("protocols.atto",
                CoreLibrarySources.oresCoreFileName("ores.core/protocols"));
        assertEquals(CoreLibrarySources.oresCoreUnitId("ores.core/protocols"),
                CoreLibrarySources.oresCoreUnitId("ores.core/protocols.atto"));
        assertEquals(CoreLibrarySources.oresCoreUnitId("ores.core/protocols"),
                CoreLibrarySources.oresCoreUnitId("ores.core/protocols.ores"));
        String source = CoreLibrarySources.oresCoreSource("ores.core/protocols");
        assertTrue(source.contains("define pub trait Read<E>"));
        assertTrue(source.contains("define pub trait Write<E>"));
        assertTrue(source.contains("define pub trait IntoIterator<T>"));
        assertTrue(source.contains("define pub trait Service<Request, Reply, E>"));
        assertTrue(source.contains("define pub trait KeyValueStore<K, V, E>"));
        assertTrue(source.contains("define pub trait BatchWritable<T, E>"));
        assertTrue(source.contains("define pub trait Transform<I, O, E>"));
        assertTrue(source.contains("define pub trait ReadWrite<E>"));
        assertTrue(source.contains("define pub trait TryFrom<Src, Dst, E>"));
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
    }

    @Test
    void explicitTraitSelectorsResolveFromCoreNotAmbientFilesystem() throws Exception {
        Path guest = temp.resolve("main.atto");
        Files.writeString(guest, """
                import trait Read from "ores.core/protocols";
                import trait Write from "ores.core/protocols";
                import trait Source from "ores.core/protocols";
                import trait Eq from "ores.core/protocols";
                import trait TryFrom from "ores.core/protocols";
                import trait ReadWrite from "ores.core/protocols";
                import trait Transform from "ores.core/protocols";
                import trait BatchWritable from "ores.core/protocols";
                import trait KeyValueStore from "ores.core/protocols";
                pub fnc main(): void { return; }
                """);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(guest));
    }

    @Test
    void importedCoreTraitRequiresActualNominalMethodConformance() throws Exception {
        Path accepted = temp.resolve("good.atto");
        Files.writeString(accepted, """
                import trait Eq from "ores.core/protocols";
                define class Counter implements Eq<int> as
                  pub equals(int other): bool { return true; }
                end
                pub fnc main(): void { return; }
                """);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(accepted));

        Path rejected = temp.resolve("bad.atto");
        Files.writeString(rejected, """
                import trait Eq from "ores.core/protocols";
                define class Counter implements Eq<int> as
                end
                pub fnc main(): void { return; }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(rejected));
    }

    @Test
    void coreProtocolNamesCannotBeDefinedAsUserClaimedSafetyAuthorities() throws Exception {
        String source = CoreLibrarySources.oresCoreSource("ores.core/protocols");
        for (String forbidden : new String[] {
                "trait Sendable", "trait Shareable", "trait Copy",
                "trait Drop", "trait Receives", "trait Supervisor"
        }) {
            assertFalse(source.contains("define pub " + forbidden),
                    "safety authority must be compiler/runtime-owned: " + forbidden);
        }
        assertFalse(source.contains("fnc read_blocking("));
        assertFalse(source.contains("fnc write_blocking("));
    }

    @Test
    void canonicalAttoFixturesActuallyExecuteTraitMethods() throws Exception {
        for (String fixture : new String[]{
                "eq-positive.atto", "ord-inherited-positive.atto",
                "eq-interface-dispatch-positive.atto"}) {
            Path source = Path.of("tests", "fixtures", "core-protocols-v1", fixture);
            assertTrue(Files.isRegularFile(source), "missing real .atto fixture " + fixture);
            // Admission alone does not prove trait method dispatch.
            assertDoesNotThrow(() -> LinkedProgramRunner.validate(source, Map.of()), fixture);
            var result = LinkedProgramRunner.runWithExitStatus(
                    source, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    new ByteArrayOutputStream(), new ByteArrayOutputStream());
            assertEquals(42, result.exitStatus(),
                    "the real linked Atto guest must execute Eq/Ord methods: " + fixture);
        }
    }

    @Test
    void canonicalHashAndCloneTraitsExecuteRealLinkedAttoGuestCode() throws Exception {
        // Positive compiler/guest tests: not merely parser acceptance and not
        // reflection on Java-side implementations. Native .atto source owns
        // both nominal trait implementations and method bodies.
        for (String fixture : new String[]{
                "hash-positive.atto", "clone-positive.atto"}) {
            Path source = Path.of("tests", "fixtures",
                    "core-protocols-java-execution", fixture);
            assertTrue(Files.isRegularFile(source),
                    "missing real .atto guest fixture " + fixture);
            assertDoesNotThrow(() -> LinkedProgramRunner.validate(source, Map.of()), fixture);
            var execution = LinkedProgramRunner.runWithExitStatus(
                    source, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    new ByteArrayOutputStream(), new ByteArrayOutputStream());
            assertEquals(42, execution.exitStatus(),
                    "compiled Atto guest must invoke Hash/Clone methods: " + fixture);
        }
    }

    @Test
    void canonicalAttoNegativeFixturesRejectInvalidConformance() {
        for (String fixture : new String[]{
                "eq-missing-negative.atto", "eq-wrong-signature-negative.atto",
                "eq-private-method-negative.atto", "ord-inherited-missing-negative.atto",
                "eq-interface-impostor-negative.atto"}) {
            Path source = Path.of("tests", "fixtures", "core-protocols-v1", fixture);
            assertTrue(Files.isRegularFile(source), "missing negative .atto fixture " + fixture);
            assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(source, Map.of()), fixture);
        }
    }


    @Test
    void missingMethodsInExtendedCoreTraitsAreRejectedByRealLinker() throws Exception {
        // These are negative compilation checks, not fabricated I/O executions.
        String[][] invalid = {
                {"TryFrom", "TryFrom<int, int, int>"},
                {"ReadWrite", "ReadWrite<int>"},
                {"Transform", "Transform<int, int, int>"},
                {"BatchWritable", "BatchWritable<int, int>"},
                {"KeyValueStore", "KeyValueStore<int, int, int>"}
        };
        for (String[] entry : invalid) {
            Path guest = temp.resolve("missing-" + entry[0] + ".atto");
            String eol = System.lineSeparator();
            Files.writeString(guest,
                    "import trait " + entry[0] + " from \"ores.core/protocols\";" + eol
                    + "define class Broken implements " + entry[1] + " as end" + eol
                    + "pub fnc main(): void { return; }" + eol);
            assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(guest, Map.of()), entry[0]);
        }
    }

    @Test
    void guestCannotForgeCompilerOwnedAutoTraitsOrSupervisorProtocols() {
        for (String name : new String[]{
                "Sendable", "Shareable", "Copy", "Drop",
                "Receives", "Supervisor", "ActorLifecycle"}) {
            for (String kind : new String[]{"trait", "interface", "contract"}) {
                String source = "define pub " + kind + " " + name
                        + " as fnc grant() => bool; end";
                IllegalArgumentException denied = assertThrows(
                        IllegalArgumentException.class, () -> Parser.parse(source),
                        "unsafe user declaration " + kind + " " + name);
                assertTrue(denied.getMessage().contains("compiler/runtime-owned"),
                        denied.getMessage());
            }
        }
        // User classes are not automatic safety proofs; conventional names
        // remain legal as ordinary value types.
        assertDoesNotThrow(() -> Parser.parse("define class Sendable as end"));
    }


    @Test
    void allCanonicalProtocolImportSpellingsResolveToOneCompilerOwnedSource() throws Exception {
        String source = CoreLibrarySources.oresCoreSource("ores.core/protocols");
        String unit = CoreLibrarySources.oresCoreUnitId("ores.core/protocols");
        for (String alias : new String[] {
                "ores.core/protocols", "ores.core/protocols.atto",
                "ores.core/protocols.ores"
        }) {
            assertEquals(source, CoreLibrarySources.oresCoreSource(alias), alias);
            assertEquals(unit, CoreLibrarySources.oresCoreUnitId(alias), alias);
        }
        for (String invalid : new String[] {
                "ores.core/../protocols", "ores.core/./protocols",
                "ores.core/protocols/../numeric", "ores.core/protocols//child",
                "ores.core/protocols.atto/child", "ores.core/%2e%2e/protocols",
                "ores.core\\protocols", ("ores.core/protocols" + (char) 0),
                "ores.std/protocols", "ores.core/"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.oresCoreSource(invalid), invalid);
        }
    }

    @Test
    void safetyAuthorityNamesRejectBeforeParsingAnyInvalidMembers() {
        // The authority check must be at the shared declaration-name ingress.
        // Otherwise an alternate trait/interface/contract path could reach
        // ordinary nominal conformance before the reservation is checked.
        for (String name : new String[] {
                "Sendable", "Shareable", "Copy", "Drop", "Receives",
                "Supervisor", "ActorLifecycle"
        }) {
            for (String kind : new String[] {"trait", "interface", "contract"}) {
                String source = "define pub " + kind + " " + name
                        + " as this_is_not_an_interface_member; end";
                IllegalArgumentException failure = assertThrows(
                        IllegalArgumentException.class, () -> Parser.parse(source),
                        kind + " " + name);
                assertTrue(failure.getMessage().contains("compiler/runtime-owned"),
                        kind + " " + name + ": " + failure.getMessage());
            }
        }
    }

    @Test
    void inheritedGenericRequirementCannotBeSatisfiedByWrongParameterType() throws Exception {
        Path wrong = temp.resolve("wrong-inherited-generic.atto");
        Files.writeString(wrong, """
                import trait Ord from "ores.core/protocols";
                define pub class Counter implements Ord<int> as
                  pub equals(String other): bool { return true; }
                  pub compare(int other): int { return 0; }
                end
                pub fnc main(): void { return; }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(wrong, Map.of()),
                "Ord<int> must inherit Eq<int>, not accept Eq<String>");
    }



    @Test
    void parsedProtocolKindSurvivesTheAstAndRejectsForgedContractKind() {
        var program = Parser.parse("""
                define pub trait Signed as
                    fnc verify(int value): bool;
                end
                define pub interface Shape as
                    fnc area(): int;
                end
                """);
        var declarations = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(dev.oreslang.ast.Ast.InterfaceDecl.class::isInstance)
                .map(dev.oreslang.ast.Ast.InterfaceDecl.class::cast)
                .toList();
        assertEquals(2, declarations.size());
        assertEquals(dev.oreslang.ast.Ast.InterfaceKind.TRAIT,
                declarations.get(0).declarationKind());
        assertEquals(dev.oreslang.ast.Ast.InterfaceKind.INTERFACE,
                declarations.get(1).declarationKind());
        assertThrows(IllegalArgumentException.class,
                () -> new dev.oreslang.ast.Ast.InterfaceDecl(
                        "Forged", dev.oreslang.ast.Ast.Visibility.PUBLIC,
                        java.util.List.of(), java.util.List.of(),
                        java.util.List.of(), true,
                        dev.oreslang.ast.Ast.InterfaceKind.TRAIT));
    }

    @Test
    void traitImportCannotMasqueradeAsInterfaceAndViceVersa() throws Exception {
        Path definitions = temp.resolve("protocol-kinds.atto");
        Files.writeString(definitions, """
                define pub trait Signed as
                    fnc verify(int value): bool;
                end
                define pub interface Shape as
                    fnc area(): int;
                end
                """);
        for (String invalid : new String[] {
                "import trait Shape from './protocol-kinds.atto';",
                "import interface Signed from './protocol-kinds.atto';",
                "import interface Eq from 'ores.core/protocols';"
        }) {
            Path client = temp.resolve("mismatch.atto");
            Files.writeString(client, invalid
                    + "\npub fnc main(): void { return; }\n");
            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(client, Map.of()), invalid);
            assertTrue(rejected.getMessage().contains(
                    "does not match an exported declaration"), rejected.toString());
        }
        Path accepted = temp.resolve("matched.atto");
        Files.writeString(accepted, """
                import trait Signed from './protocol-kinds.atto';
                import interface Shape from './protocol-kinds.atto';
                pub fnc main(): void { return; }
                """);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(accepted, Map.of()));
    }

    @Test
    void allNineteenCoreTraitsRejectMissingNominalWitnesses() throws Exception {
        // Pure compiler negatives: no host I/O provider or actor privilege.
        String[][] cases = {
                {"Eq", "Eq<int>"}, {"Ord", "Ord<int>"},
                {"Hash", "Hash"}, {"Clone", "Clone<int>"},
                {"Read", "Read<int>"}, {"Write", "Write<int>"},
                {"Flush", "Flush<int>"}, {"Close", "Close"},
                {"AsyncClose", "AsyncClose<int>"}, {"Seek", "Seek<int>"},
                {"Source", "Source<int, int>"}, {"Sink", "Sink<int, int>"},
                {"IntoIterator", "IntoIterator<int>"},
                {"Service", "Service<int, int, int>"},
                {"TryFrom", "TryFrom<int, int, int>"},
                {"ReadWrite", "ReadWrite<int>"},
                {"Transform", "Transform<int, int, int>"},
                {"BatchWritable", "BatchWritable<int, int>"},
                {"KeyValueStore", "KeyValueStore<int, int, int>"}
        };
        assertEquals(19, cases.length);
        for (String[] entry : cases) {
            Path guest = temp.resolve("missing-" + entry[0].toLowerCase() + ".atto");
            Files.writeString(guest,
                    "import trait " + entry[0] + " from \"ores.core/protocols\";\n"
                    + "define class Broken implements " + entry[1] + " as end\n"
                    + "pub fnc main(): void { return; }\n");
            assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(guest, Map.of()),
                    "missing inherited/direct method witness for " + entry[0]);
        }
    }

    @Test
    void unknownCoreModulesFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreSource("ores.core/protocols/../ambient"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreSource("ores.core/unregistered"));
    }
}
