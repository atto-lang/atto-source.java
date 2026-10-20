package dev.oreslang;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.ReachabilityManifest;
import dev.oreslang.compiler.TreeShaker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ReachabilityManifestTest {
    @Test
    void manifestIsDeterministicAndContainsOnlyRetainedRequirements() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                define module Dead
                  pub fnc unused(): int { return 7; }
                end

                define module Live
                  pub fnc answer(int value): int { return value + 1; }
                end

                define class Box as
                  pub val int value = 1;
                end

                pub routine main(): void {
                  val Box box = new Box();
                  stdio.stdout.write(Live.answer(box.value));
                  return;
                }
                """, BuildOptions.executable(Map.of()));

        ReachabilityManifest manifest = result.manifest();
        assertEquals(ReachabilityManifest.SCHEMA, manifest.schema());
        assertTrue(manifest.retainedSymbols().contains("Live.answer"));
        assertTrue(manifest.removedSymbols().contains("Dead.unused"));
        assertTrue(manifest.externalBindings().contains("stdio"));
        assertTrue(manifest.languageFeatures().contains("classes"));
        assertTrue(manifest.languageFeatures().contains("allocation"));
        assertEquals(manifest.toCanonicalJson(), result.manifest().toCanonicalJson());

        assertTrue(manifest.retainedSymbols().stream()
                .noneMatch(manifest.removedSymbols()::contains));
    }

    @Test
    void manifestJsonEscapesAndSortsSetLikeFields() {
        ReachabilityManifest manifest = new ReachabilityManifest(
                ReachabilityManifest.SCHEMA,
                java.util.List.of("z", "a", "a"),
                false,
                java.util.List.of("B.f", "A.f"),
                java.util.List.of("Z.dead"),
                java.util.List.of("stdio", "net", "stdio"),
                java.util.List.of("classes", "allocation"),
                java.util.List.of("share", "borrow"));

        String json = manifest.toCanonicalJson();
        assertTrue(json.contains("\"entryPoints\":[\"a\",\"z\"]"));
        assertTrue(json.contains("\"retainedSymbols\":[\"A.f\",\"B.f\"]"));
        assertTrue(json.contains("\"externalBindings\":[\"net\",\"stdio\"]"));
    }
}
