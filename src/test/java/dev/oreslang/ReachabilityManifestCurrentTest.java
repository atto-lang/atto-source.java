package dev.oreslang;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.ReachabilityManifest;
import dev.oreslang.compiler.TreeShaker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ReachabilityManifestCurrentTest {
    @Test
    void sharedScalarFixtureMatchesExactCanonicalReceipt() throws IOException {
        String source = resource("reachability/scalar-direct-call.ores");
        String expected = resource("reachability/scalar-direct-call.json").stripTrailing();

        TreeShaker.Result result =
                OresCompiler.compileForBuild(source, BuildOptions.executable());
        ReachabilityManifest manifest =
                ReachabilityManifest.from(result);

        assertEquals(Set.of("__root__.main"), result.rootSymbols());
        assertTrue(result.retainedSymbols().contains("__root__.helper"),
                "shared fixture helper must remain a runtime dependency");
        assertEquals(expected, manifest.canonicalJson());
        assertEquals(64, manifest.sha256().length());
    }

    @Test
    void manifestUsesTreeShakerRootsWithoutRecomputation() {
        TreeShaker.Result base = OresCompiler.compileForBuild("""
                fnc helper(): int {
                  const value: int = 41;
                  return value;
                }
                pub fnc answer(): int { return helper() + 1; }
                fnc dead(): int { return 0; }
                """, BuildOptions.library(Map.of()));

        ReachabilityManifest manifest =
                ReachabilityManifest.from(base);

        assertEquals(
                base.rootSymbols().stream().sorted().toList(),
                manifest.roots());
        assertEquals(java.util.List.of("__root__.answer"), manifest.roots());
        assertTrue(manifest.retainedSymbols().contains("__root__.helper"));
        assertTrue(manifest.removedSymbols().contains("__root__.dead"));
    }

    @Test
    void overloadedLexicalIdentityFailsClosedUntilSharedContractExists() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.compileForBuild("""
                        fnc pick(int value): int { return value; }
                        fnc pick(int left, int right): int { return left + right; }
                        pub fnc main(): int { return pick(1); }
                        """, BuildOptions.executable()));
    }

    @Test
    void mismatchedBuildOptionsCannotRelabelManifest() {
        TreeShaker.Result result = OresCompiler.compileForBuild(
                "pub fnc answer(): int { return 42; }",
                BuildOptions.library(Map.of()));

        assertTrue(result.libraryBuild());
        assertThrows(
                IllegalArgumentException.class,
                () -> ReachabilityManifest.from(
                        result, BuildOptions.executable()));
        assertEquals(
                "library",
                ReachabilityManifest.from(result).buildKind());
    }

    @Test
    void forgedRootOutsideRetainedSetFailsClosed() {
        TreeShaker.Result base = OresCompiler.compileForBuild(
                "pub fnc main(): int { return 0; }",
                BuildOptions.executable());

        TreeShaker.Result forged = new TreeShaker.Result(
                base.program(),
                base.retainedSymbols(),
                base.removedSymbols(),
                base.compileTimeConstants(),
                Set.of("__root__.missing"),
                base.libraryBuild(),
                base.semanticSymbols());

        assertThrows(
                IllegalArgumentException.class,
                () -> ReachabilityManifest.from(
                        forged, BuildOptions.executable()));
    }

    @Test
    void retainedRemovedOverlapFailsClosed() {
        TreeShaker.Result base = OresCompiler.compileForBuild(
                "pub fnc main(): int { return 0; }",
                BuildOptions.executable());

        TreeShaker.Result forged = new TreeShaker.Result(
                base.program(),
                Set.of("__root__.main"),
                Set.of("__root__.main"),
                base.compileTimeConstants(),
                Set.of("__root__.main"),
                base.libraryBuild(),
                base.semanticSymbols());

        assertThrows(
                IllegalArgumentException.class,
                () -> ReachabilityManifest.from(
                        forged, BuildOptions.executable()));
    }

    @Test
    void incompleteSemanticClassificationFailsClosed() {
        TreeShaker.Result base = OresCompiler.compileForBuild(
                "pub fnc main(): int { return 0; }",
                BuildOptions.executable());

        TreeShaker.Result forged = new TreeShaker.Result(
                base.program(),
                base.retainedSymbols(),
                base.removedSymbols(),
                base.compileTimeConstants(),
                base.rootSymbols(),
                base.libraryBuild(),
                Set.of("__root__.main", "__root__.missing"));

        assertThrows(
                IllegalArgumentException.class,
                () -> ReachabilityManifest.from(forged));
    }

    @Test
    void nullCompileTimeConstantRemainsCanonicalJsonNull() {
        TreeShaker.Result base = OresCompiler.compileForBuild(
                "pub fnc main(): int { return 0; }",
                BuildOptions.executable());
        LinkedHashMap<String, Object> constants = new LinkedHashMap<>();
        constants.put("__root__.optional", null);

        TreeShaker.Result forged = new TreeShaker.Result(
                base.program(),
                base.retainedSymbols(),
                base.removedSymbols(),
                constants,
                base.rootSymbols(),
                base.libraryBuild(),
                base.semanticSymbols());

        assertEquals(
                null,
                ReachabilityManifest.from(forged)
                        .compileTimeConstants()
                        .get("__root__.optional"));
        assertTrue(
                ReachabilityManifest.from(forged)
                        .canonicalJson()
                        .contains("\"__root__.optional\":null"));
    }

    @Test
    void nullConstantKeyFailsClosedDeterministically() {
        TreeShaker.Result base = OresCompiler.compileForBuild(
                "pub fnc main(): int { return 0; }",
                BuildOptions.executable());
        LinkedHashMap<String, Object> constants = new LinkedHashMap<>();
        constants.put(null, 1L);

        TreeShaker.Result forged = new TreeShaker.Result(
                base.program(),
                base.retainedSymbols(),
                base.removedSymbols(),
                constants,
                base.rootSymbols(),
                base.libraryBuild(),
                base.semanticSymbols());

        assertThrows(
                IllegalArgumentException.class,
                () -> ReachabilityManifest.from(forged));
    }

    @Test
    void malformedUnicodeConstantFailsClosed() {
        TreeShaker.Result base = OresCompiler.compileForBuild(
                "pub fnc main(): int { return 0; }",
                BuildOptions.executable());

        TreeShaker.Result forged = new TreeShaker.Result(
                base.program(),
                base.retainedSymbols(),
                base.removedSymbols(),
                Map.of("__root__.label", "\uD800"),
                base.rootSymbols(),
                base.libraryBuild(),
                base.semanticSymbols());

        assertThrows(
                IllegalArgumentException.class,
                () -> ReachabilityManifest.from(
                        forged, BuildOptions.executable()));
    }

    private static String resource(String name) throws IOException {
        try (InputStream input =
                     ReachabilityManifestCurrentTest.class
                             .getClassLoader()
                             .getResourceAsStream(name)) {
            assertNotNull(input, "missing test resource " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
