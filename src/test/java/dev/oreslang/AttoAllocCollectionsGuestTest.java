package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real AttoLang guest checks of independently authored .atto alloc libraries.
 * These do not substitute host-language models for collection operations.
 * The guest fixtures import exact, staged compiler-owned source using ordinary
 * package-relative imports; atto.alloc/* package resolution is not claimed.
 */
final class AttoAllocCollectionsGuestTest {
    private static final Path ROOT =
            Path.of("tests", "fixtures", "alloc-collections-v1");
    @TempDir Path temp;

    @Test
    void allocModulesParseAsAttoLangSource() throws Exception {
        for (String module : new String[] {"bitset", "linked_list", "map"}) {
            Path path = Path.of("stdlib", "alloc", module + ".atto");
            assertTrue(Files.isRegularFile(path), "missing real .atto module " + path);
            String code = Files.readString(path);
            assertFalse(code.contains("import java"));
            assertFalse(code.contains("import System"));
            assertDoesNotThrow(() -> Parser.parse(code), "Atto parser: " + module);
        }
    }

    @Test
    void bitsetGuestExecutesWordBoundariesAndIntersections() throws Exception {
        execute("bitset.atto");
    }

    @Test
    void linkedListGuestExecutesOrderingAndRecycling() throws Exception {
        execute("linked_list.atto");
    }

    @Test
    void mapGuestExecutesCollisionsTombstonesAndQuota() throws Exception {
        execute("map.atto");
    }

    private void execute(String name) throws Exception {
        Path fixture = ROOT.resolve(name);
        Path module = Path.of("stdlib", "alloc", name);
        assertTrue(Files.isRegularFile(fixture), "missing .atto fixture " + fixture);
        assertTrue(Files.isRegularFile(module), "missing .atto module " + module);
        // Stage exact original module bytes, without host-generated guest code
        // or filesystem traversal imports. The fixtures import ./module.atto.
        Path stagedModule = temp.resolve(name);
        Files.copy(module, stagedModule);
        assertEquals(-1, Files.mismatch(module, stagedModule),
                "staging altered compiler-owned Atto source");
        Path path = temp.resolve("main.atto");
        Files.copy(fixture, path);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(path, Map.of()),
                "typecheck and link actual alloc source: " + name);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.runWithExitStatus(
                path, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                stdout, stderr);
        assertEquals(42, result.exitStatus(),
                () -> "Atto guest returned unexpected exit code for " + name
                        + ", stderr=" + stderr);
    }
}
