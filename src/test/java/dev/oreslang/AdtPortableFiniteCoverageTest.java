package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact-byte portable Option/Result ADT subset; does NOT establish
 * source-level user-defined nominal enum parity on any backend.
 */
final class AdtPortableFiniteCoverageTest {
    private static byte[] fixture(String id, String gitBlobSha) throws Exception {
        try (InputStream in = AdtPortableFiniteCoverageTest.class
                .getResourceAsStream("/adt-parity/" + id + ".ores")) {
            assertNotNull(in, "missing canonical .ores fixture " + id);
            byte[] bytes = in.readAllBytes();
            assertTrue(bytes.length > 0 && bytes.length <= 1_048_576,
                    "unbounded or empty Oreslang fixture");
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            digest.update(bytes);
            assertEquals(gitBlobSha, HexFormat.of().formatHex(digest.digest()),
                    "canonical Git blob has drifted: " + id);
            return bytes;
        }
    }

    private static void accepts(String program) {
        assertDoesNotThrow(() -> {
            var typed = TypeChecker.check(Parser.parse(program));
            OwnershipChecker.check(typed);
        });
    }

    private static IllegalArgumentException rejects(String program, String diagnostic) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(error.getMessage().contains(diagnostic), error.getMessage());
        return error;
    }

    @Test
    void sharedFiniteOptionResultGuestRunsWithByteExactOutput() throws Exception {
        byte[] bytes = fixture("option-result-partitions",
                "71815d126707ab13ed54b9dde4ba2645c7c8472b");
        String program = new String(bytes, StandardCharsets.UTF_8);
        accepts(program);
        var source = Source.newBuilder(OresLanguage.ID, program, "option-result-partitions.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        var output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("1\n0\n3\n6\n", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void sharedNonExhaustiveAndDeadArmCasesAreSemanticCompilerRejections() throws Exception {
        rejects(new String(fixture("option-partial-int",
                "9af4f4c1bdd1fa8547ea86607b386415a6c6bf2e"),
                StandardCharsets.UTF_8), "non-exhaustive");
        rejects(new String(fixture("result-partial-bool",
                "c56cb551f244e137f00e5375fd50c3c7f06d5550"),
                StandardCharsets.UTF_8), "non-exhaustive");
        rejects(new String(fixture("unreachable-after-bool-partition",
                "6fc79dd1a7e357f1f726fc1957ebea337b8a45b0"),
                StandardCharsets.UTF_8), "unreachable");
    }

    @Test
    void nestedProductOfSumsMustCoverAllFiniteInhabitants() {
        accepts("""
            fnc classify(Result<Option<bool>, bool> value): int {
                match value over
                    on Ok(Some(true)) -> { return 1; }
                    on Ok(Some(false)) -> { return 2; }
                    on Ok(None) -> { return 3; }
                    on Err(true) -> { return 4; }
                    on Err(false) -> { return 5; }
                end
            }
            """);
        rejects("""
            fnc bad(Result<Option<bool>, bool> value): int {
                match value over
                    on Ok(Some(true)) -> { return 1; }
                    on Ok(None) -> { return 3; }
                    on Err(true) -> { return 4; }
                    on Err(false) -> { return 5; }
                end
            }
            """, "non-exhaustive");
    }

    @Test
    void guardsDoNotSilentlyMakeOptionPayloadCoverageTotal() {
        rejects("""
            fnc bad(Option<bool> value): int {
                match value over
                    on Some(true) when true -> { return 1; }
                    on Some(false) -> { return 2; }
                    on None -> { return 0; }
                end
            }
            """, "non-exhaustive");
        accepts("""
            fnc good(Option<bool> value): int {
                match value over
                    on Some(true) when true -> { return 1; }
                    on Some(true) -> { return 7; }
                    on Some(false) -> { return 2; }
                    on None -> { return 0; }
                end
            }
            """);
    }

    @Test
    void invalidForeignConstructorAndIllTypedGuardCannotDischargeCoverage() {
        rejects("""
            fnc bad(Option<int> value): int {
                match value over
                    on Err(error) -> { return 0; }
                    on _ -> { return 1; }
                end
            }
            """, "constructor");
        rejects("""
            fnc bad(Option<bool> value): int {
                match value over
                    on Some(true) when 7 -> { return 1; }
                    on Some(false) -> { return 2; }
                    on None -> { return 0; }
                end
            }
            """, "match guard");
    }
}
