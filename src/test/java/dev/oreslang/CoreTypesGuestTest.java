package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real linked Atto guest gate; the source-only Node oracle cannot satisfy it. */
final class CoreTypesGuestTest {
    private static final Path FIXTURES = Path.of("tests", "fixtures", "core-types-v1");

    @Test
    void coreTypesPublicLibraryParsesAndChecksWithoutHostInterop() throws Exception {
        Path module = Path.of("core", "types.atto");
        assertTrue(Files.isRegularFile(module));
        String source = Files.readString(module);
        assertTrue(source.contains("use namespace types;"));
        assertFalse(source.contains("define pub module types as"));
        assertEquals("types", Parser.parseStrictAtto(source).namespace());
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
    }

    @Test
    void invalidNamespaceHeadersRejectAtParserNotOnlyAtRuntime() {
        for (String fixture : new String[] {
                "namespace-duplicate-negative.atto", "namespace-late-negative.atto"
        }) {
            Path input = FIXTURES.resolve(fixture);
            assertTrue(Files.isRegularFile(input), fixture);
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parseStrictAtto(Files.readString(input)), fixture);
        }
    }

    @Test
    void sourceImportedByRealGuestExecutesBoundaries() throws Exception {
        for (String fixture : new String[] { "edge-positive.atto", "edge-matrix-positive.atto" }) {
            Path guest = FIXTURES.resolve(fixture);
            assertTrue(Files.isRegularFile(guest), fixture);
            assertDoesNotThrow(() -> LinkedProgramRunner.validate(guest, Map.of()), fixture);
            var result = LinkedProgramRunner.runWithExitStatus(
                    guest, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    new ByteArrayOutputStream(), new ByteArrayOutputStream());
            assertEquals(0, result.exitStatus(),
                    "real compiled/linked Atto guest must verify core/types boundaries: " + fixture);
        }
    }

    @Test
    void negativeTypeAndWidthFixturesCannotBecomeExecutable() {
        for (String fixture : new String[] {
                "void-value-negative.atto",
                "void-return-negative.atto",
                "bool-truthiness-negative.atto",
                "i64-literal-overflow-negative.atto",
                "wrong-arg-negative.atto",
                "u64-outside-int-negative.atto",
                "namespace-duplicate-negative.atto",
                "namespace-late-negative.atto"
        }) {
            Path input = FIXTURES.resolve(fixture);
            assertTrue(Files.isRegularFile(input), fixture);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(input, Map.of()), fixture);
            assertNotNull(e.getMessage(), fixture);
            assertFalse(e.getMessage().isBlank(), fixture);
        }
    }
}
