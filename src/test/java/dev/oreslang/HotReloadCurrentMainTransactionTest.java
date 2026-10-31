package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.runtime.IsolatePolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual source-backed generic loader admission and publication tests.
 * Does not establish actor mailbox migration, protected isolates, or native AOT.
 */
final class HotReloadCurrentMainTransactionTest {
    private static final String GOOD = "pub routine main(): void { return; }";
    private static final String BAD_AT_START = """
            pub routine main(): void {
              val values = arr[1];
              val value = values[99];
              return;
            }
            """;
    private static final ExecutionProfile PROFILE = ExecutionProfile.serverJit();

    private static String sha256(String source) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static IncrementalCompiler.CompiledUnit compiledGood() {
        return new IncrementalCompiler().compile(Map.of("app.ores", GOOD))
                .units().get("app.ores");
    }

    private static IncrementalCompiler.CompiledUnit counterfeit(
            IncrementalCompiler.CompiledUnit original, String source,
            String digest, Set<String> dependencies) {
        return new IncrementalCompiler.CompiledUnit(
                original.unitId(), original.packageId(), original.namespace(),
                digest, original.abiDigest(), dependencies, source, original.program());
    }

    @Test
    void failedStartupPreservesExactPreviouslyServingGeneration() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var previous = manager.loadAndStart("worker.ores", GOOD);
            assertThrows(RuntimeException.class,
                    () -> manager.reloadAndStart("worker.ores", BAD_AT_START));
            assertSame(previous, manager.active("worker.ores"));
            assertSame(previous, manager.active());
            assertFalse(previous.closed());
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void successfulStartupPublishesOnlyStartedCandidate() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var previous = manager.loadAndStart("worker.ores", GOOD);
            var current = manager.reloadAndStart("worker.ores",
                    "pub routine main(): void { val version = 2; return; }");
            assertTrue(current.started());
            assertSame(current, manager.active("worker.ores"));
            assertFalse(previous.closed());
            assertEquals(2, manager.liveGenerations());
            manager.retire(previous.id());
            assertSame(current, manager.active("worker.ores"));
            assertTrue(previous.closed());
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void failedOtherUnitNeverRedirectsGlobalLastServingRoute() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var previous = manager.loadAndStart("stable.ores", GOOD);
            assertThrows(RuntimeException.class,
                    () -> manager.loadAndStart("broken.ores", BAD_AT_START));
            assertSame(previous, manager.active());
            assertNull(manager.active("broken.ores"));
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void legacyLoadRemainsExplicitlyUnreadyForServing() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var staged = manager.load("legacy.ores", GOOD);
            assertFalse(staged.started());
            // The existing load() publication API is compatibility-only; use
            // reloadAndStart() as the serving-route upgrade entry point.
            assertSame(staged, manager.active("legacy.ores"));
        }
    }

    @Test
    void forgedSourceDigestCannotStageOrEvictHealthyRoute() {
        var compiled = compiledGood();
        var forged = counterfeit(compiled, "unrelated", compiled.sourceDigest(), Set.of());
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var serving = manager.loadAndStart("healthy.ores", GOOD);
            assertThrows(SecurityException.class, () -> manager.load(forged));
            assertSame(serving, manager.active());
            assertNull(manager.active("app.ores"));
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void matchingDigestDoesNotAuthorizeUnverifiedAst() {
        var compiled = compiledGood();
        String invalid = "pub routine main(): void {";
        var forged = counterfeit(compiled, invalid, sha256(invalid), Set.of());
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            assertThrows(RuntimeException.class, () -> manager.load(forged));
            assertNull(manager.active());
            assertEquals(0, manager.liveGenerations());
        }
    }

    @Test
    void forgedEmptyDependencySetCannotHideRealExecutableImport() {
        var compiled = compiledGood();
        String imported = "import module dependency from \"dependency.ores\";\n" + GOOD;
        assertFalse(dev.oreslang.parser.Parser.parse(imported).imports().isEmpty());
        var forged = counterfeit(compiled, imported, sha256(imported), Set.of());
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var serving = manager.loadAndStart("healthy.ores", GOOD);
            assertThrows(SecurityException.class, () -> manager.load(forged));
            assertSame(serving, manager.active("healthy.ores"));
            assertNull(manager.active("app.ores"));
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void managerShutdownFencesAnyUnstartedGenerationEntrypoint() {
        var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE);
        var candidate = manager.load("not-started.ores", GOOD);
        manager.close();
        assertTrue(candidate.closed());
        assertThrows(IllegalStateException.class, candidate::start);
        assertEquals(0, manager.liveGenerations());
    }

    @Test
    void retirementFencesAnyUnstartedGenerationEntrypoint() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var candidate = manager.load("retire-first.ores", GOOD);
            manager.retire(candidate.id());
            assertTrue(candidate.closed());
            assertNull(manager.active("retire-first.ores"));
            assertThrows(IllegalStateException.class, candidate::start);
            assertEquals(0, manager.liveGenerations());
        }
    }

    @Test
    void directGenerationCloseFencesEntrypointAndEvictsServingPointer() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var candidate = manager.load("direct-close.ores", GOOD);
            candidate.close();
            assertTrue(candidate.closed());
            assertNull(manager.active("direct-close.ores"));
            assertThrows(IllegalStateException.class, candidate::start);
            assertEquals(0, manager.liveGenerations());
        }
    }

    @Test
    void unverifiedDeclaredDependenciesAreRejectedEvenWithValidSource() {
        var compiled = compiledGood();
        var forged = counterfeit(compiled, GOOD, sha256(GOOD), Set.of("other.ores"));
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            assertThrows(SecurityException.class, () -> manager.load(forged));
            assertEquals(0, manager.liveGenerations());
        }
    }
}
