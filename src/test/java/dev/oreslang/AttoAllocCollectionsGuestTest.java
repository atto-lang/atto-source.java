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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real AttoLang guest checks of independently authored .atto alloc libraries.
 * These do not substitute host-language models for collection operations.
 * The source tests use an explicit repository-relative import until a stable
 * compiler-owned atto.alloc/* package resolver is installed.
 */
final class AttoAllocCollectionsGuestTest {
    private static final Path ROOT =
            Path.of("tests", "fixtures", "alloc-collections-v1");

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
        Path path = ROOT.resolve(name);
        assertTrue(Files.isRegularFile(path), "missing .atto fixture " + path);
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
