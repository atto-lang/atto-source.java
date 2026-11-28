package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class HotReloadCompiledUnitAdmissionTest {
    private static final String GOOD = "pub routine main(): void { return; }";
    private static final String INVALID =
            "pub routine main(): void { nonexistent_host_operation(); return; }";

    private static String digest(String source) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static IncrementalCompiler.CompiledUnit fixture() {
        return new IncrementalCompiler().compile(Map.of("app.ores", GOOD))
                .units().get("app.ores");
    }

    private static IncrementalCompiler.CompiledUnit counterfeit(
            IncrementalCompiler.CompiledUnit base,
            String source,
            String hash,
            Set<String> dependencies) {
        return new IncrementalCompiler.CompiledUnit(
                base.unitId(), base.packageId(), base.namespace(), hash,
                base.abiDigest(), dependencies, source, base.program());
    }

    @Test
    void acceptsIndependentlyValidatedSourceFromAnOrdinaryCompiledUnit() {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var unit = fixture();
            var generation = manager.load(unit);

            assertEquals(digest(GOOD), generation.sha256());
            assertSame(generation, manager.active(unit.unitId()));
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void rejectsMismatchedCachedSourceDigestBeforeStaging() {
        var base = fixture();
        var forged = counterfeit(base, INVALID, base.sourceDigest(), Set.of());

        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            assertThrows(SecurityException.class, () -> manager.load(forged));
            assertNull(manager.active());
            assertEquals(0, manager.liveGenerations());
        }
    }

    @Test
    void rejectsInvalidExecutableSourceEvenWhenItsDigestMatches() {
        var base = fixture();
        var forged = counterfeit(base, INVALID, digest(INVALID), Set.of());

        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            assertThrows(RuntimeException.class, () -> manager.load(forged));
            assertNull(manager.active());
            assertEquals(0, manager.liveGenerations());
        }
    }


    @Test
    void rejectedReplacementCannotReplaceOrRetirePreviouslyAdmittedGeneration() {
        var base = fixture();
        String imported = "import fnc {b_value} from \"./b.ores\";\n"
                + GOOD;
        // The public CompiledUnit record permits a counterfeit AST, digest,
        // dependency list or executable source. None may mutate live state.
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var original = manager.load(base);
            assertFalse(original.started(), "staging must not execute a guest");

            var badDigest = counterfeit(base, INVALID, base.sourceDigest(), Set.of());
            var badSource = counterfeit(base, INVALID, digest(INVALID), Set.of());
            var badDependencies = counterfeit(base, GOOD, digest(GOOD), Set.of("missing.ores"));
            var badImport = counterfeit(base, imported, digest(imported), Set.of());
            assertFalse(dev.oreslang.parser.Parser.parse(imported).imports().isEmpty());

            for (var attack : java.util.List.of(badDigest, badSource, badDependencies, badImport)) {
                assertThrows(RuntimeException.class, () -> manager.load(attack),
                        "counterfeit source must fail closed");
                assertSame(original, manager.active(base.unitId()));
                assertSame(original, manager.active());
                assertEquals(1, manager.liveGenerations(),
                        "failed admission must reserve no generation");
                assertFalse(original.started(), "failed admission must not execute the staged guest");
                assertFalse(original.closed(), "failed admission must not retire an existing guest");
            }

            var next = manager.load(base);
            assertEquals(original.id() + 1, next.id(),
                    "rejected upgrades must not consume generation IDs");
            assertEquals(2, manager.liveGenerations());
        }
    }

    @Test
    void rejectsUnverifiedDependencyClosureBeforeStaging() {
        var base = fixture();
        var forged = counterfeit(
                base, GOOD, digest(GOOD), Set.of("dependency.ores"));

        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            assertThrows(SecurityException.class, () -> manager.load(forged));
            assertNull(manager.active());
            assertEquals(0, manager.liveGenerations());
        }
    }

    @Test
    void rejectsActualImportsEvenWhenCallerSuppliesAnEmptyDependencySet() {
        var base = fixture();
        String source = "import fnc {b_value} from \"./b.ores\";\n"
                + "pub routine main(): void { return; }";
        assertFalse(dev.oreslang.parser.Parser.parse(source).imports().isEmpty());
        var forged = counterfeit(base, source, digest(source), Set.of());

        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            assertThrows(SecurityException.class, () -> manager.load(forged));
            assertNull(manager.active());
            assertEquals(0, manager.liveGenerations());
        }
    }
}
