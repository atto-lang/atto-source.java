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
}
