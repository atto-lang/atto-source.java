package dev.oreslang;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.ReachabilityManifest;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ReachabilityManifestTest {
    @Test
    void executableManifestIsCanonicalAndStable() {
        BuildOptions options = BuildOptions.executable(Map.of("use_a", "true"));
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                pub const bool use_a = false;

                define module A
                  pub fnc foo(): String { return "hi"; }
                end

                define module B
                  pub fnc foo(): String { return "bye"; }
                end

                type F = typeof fnc() => String;
                pub fnc choose(bool t): F { return t ? A.foo : B.foo; }

                pub routine main(): void {
                  val F selected = choose(use_a);
                  stdio.stdout.write(selected());
                  return;
                }
                """, options);

        ReachabilityManifest manifest = ReachabilityManifest.from(result, options);
        String root = Parser.ROOT_MODULE;

        assertEquals("executable", manifest.buildKind());
        assertEquals(java.util.List.of(root + ".main"), manifest.roots());
        assertTrue(manifest.retainedSymbols().contains("A.foo"));
        assertTrue(manifest.retainedSymbols().contains(root + ".main"));
        assertTrue(manifest.removedSymbols().contains("B.foo"));
        assertTrue(manifest.removedSymbols().contains(root + ".choose"));
        assertTrue(manifest.removedSymbols().contains(root + ".use_a"));
        assertEquals(Boolean.TRUE, manifest.compileTimeConstants().get(root + ".use_a"));

        String json = manifest.canonicalJson();
        assertEquals(json, ReachabilityManifest.from(result, options).canonicalJson());
        assertEquals(manifest.sha256(), ReachabilityManifest.from(result, options).sha256());
        assertEquals(64, manifest.sha256().length());
        assertTrue(json.startsWith("{\"format\":\"ores-reachability/1\",\"buildKind\":\"executable\""));
        assertTrue(json.contains("\"roots\":[\"" + root + ".main\"]"));
        assertTrue(json.indexOf("\"retainedSymbols\"") < json.indexOf("\"removedSymbols\""));
    }

    @Test
    void libraryManifestRootsOnlyPublicApiNotPrivateDependencies() {
        BuildOptions options = BuildOptions.library(Map.of());
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                fnc helper(): int { return 41; }
                pub fnc answer(): int { return helper() + 1; }
                fnc dead(): int { return 0; }
                """, options);

        ReachabilityManifest manifest = ReachabilityManifest.from(result, options);
        String root = Parser.ROOT_MODULE;

        assertEquals("library", manifest.buildKind());
        assertEquals(java.util.List.of(root + ".answer"), manifest.roots());
        assertTrue(manifest.retainedSymbols().contains(root + ".helper"));
        assertTrue(manifest.removedSymbols().contains(root + ".dead"));
        assertFalse(manifest.roots().contains(root + ".helper"),
                "private dependencies are retained but are not library roots");
    }

    @Test
    void manifestCollectionsCannotBeMutatedByBackendCode() {
        BuildOptions options = BuildOptions.executable();
        TreeShaker.Result result = OresCompiler.compileForBuild(
                "pub routine main(): void { return; }", options);
        ReachabilityManifest manifest = ReachabilityManifest.from(result, options);

        assertThrows(UnsupportedOperationException.class,
                () -> manifest.retainedSymbols().add("forged"));
        assertThrows(UnsupportedOperationException.class,
                () -> manifest.compileTimeConstants().put("forged", true));
    }
}
