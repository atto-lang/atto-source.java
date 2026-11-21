package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler admission on the exact fixture bytes from
 * ores-truffle-oreslang/oreslang-platform-conformance PR #25,
 * source commit 84c04d974ea5385294d4e8627ca0e12417580c74.
 *
 * Git blob identity proves input integrity, not compiler correctness.
 * This checks Java parser/type/ownership admission and focused GraalVM
 * interpreter runtime outputs. It does not mark any backend confirmed in the
 * conformance manifest: full JVM, Native Image and backend parity remain
 * separate verification gates.
 */
final class PinnedValueMatchConformanceTest {
    private static final Map<String, String> SOURCE_BLOBS = Map.of(
            "value-option-braced.ores", "d06e10a5c50d2794521ca0bdbc2aae11ab4e69ca",
            "value-vs-procedural.ores", "6e533b1204e2ae03098739e82fd6a0e729fb0866",
            "value-guard-over-end.ores", "1f4514dc885c52a77c1f6048d7cce50d232b50ed",
            "reject-nonexhaustive.ores", "a44aec379e32d18f502e098ab92bef3fc3375241",
            "reject-mismatched-types.ores", "5640484a4c302032b208d5c498cd1d816e66ca00",
            "reject-no-value.ores", "cfb364840df4303f40cc4a8898d5cbb2553d3b2b"
    );

    private static String readPinned(String name) throws IOException, NoSuchAlgorithmException {
        String expected = SOURCE_BLOBS.get(name);
        assertNotNull(expected, "unrecognized fixture " + name);
        String path = "/value-match/v1/" + name;
        byte[] bytes;
        try (InputStream stream = PinnedValueMatchConformanceTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing pinned source resource " + path);
            bytes = stream.readAllBytes();
        }
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        String gitSha = HexFormat.of().formatHex(sha1.digest(bytes));
        assertEquals(expected, gitSha, "fixture bytes differ from canonical Git blob: " + name);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void admit(String name) throws Exception {
        var typed = TypeChecker.check(Parser.parse(readPinned(name)));
        OwnershipChecker.check(typed);
    }

    @Test
    void allSixFixtureFilesMatchCanonicalGitBlobIdentity() throws Exception {
        for (String name : SOURCE_BLOBS.keySet()) {
            assertFalse(readPinned(name).isEmpty(), name);
        }
    }

    @Test
    void threeCanonicalPositiveFixturesMustPassTheRealCompilerFrontend() {
        for (String name : new String[] {
                "value-option-braced.ores",
                "value-vs-procedural.ores",
                "value-guard-over-end.ores"
        }) {
            assertDoesNotThrow(() -> admit(name), "compiler rejected canonical accept fixture: " + name);
        }
    }

    @Test
    void noValueMustFailInParserWithTheSpecificDiagnostic() throws Exception {
        // A generic type/ownership failure must never masquerade as a parser verdict.
        String source = readPinned("reject-no-value.ores");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source), "missing value must be rejected by the parser");
        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("value match arms require exactly one"),
                "wrong parser rejection reason: " + failure.getMessage());
    }

    @Test
    void nonExhaustiveMustParseThenFailAtTypeCoverage() throws Exception {
        var parsed = assertDoesNotThrow(
                () -> Parser.parse(readPinned("reject-nonexhaustive.ores")),
                "non-exhaustive fixture must be syntactically valid");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed), "type checker must reject uncovered cases");
        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("non-exhaustive match"),
                "wrong type-checker rejection reason: " + failure.getMessage());
    }

    @Test
    void mixedArmTypesMustParseThenFailAtTheValueMatchTypeBoundary() throws Exception {
        var parsed = assertDoesNotThrow(
                () -> Parser.parse(readPinned("reject-mismatched-types.ores")),
                "mismatched arm types must not fail due to an unrelated parse error");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed), "type checker must reject mixed result types");
        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("value match arm"),
                "wrong type-checker rejection reason: " + failure.getMessage());
    }

    /**
     * Execute the exact, SHA-pinned positive fixture bytes, not a hand-written
     * replacement of the examples. Successful compilation alone is insufficient
     * to prove the value match returned to the expression rather than the
     * enclosing callable, or that do-match kept its procedural return.
     */
    private static String executeCheckedProgram(String program, String sourceName) throws Exception {
        var typed = TypeChecker.check(Parser.parse(program));
        OwnershipChecker.check(typed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, program, sourceName)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        // No trim(), line-ending replacement or whitespace normalization:
        // all guest output bytes matter, including accidental extra newlines.
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String executePinnedMain(String name) throws Exception {
        return executeCheckedProgram(readPinned(name), name);
    }

    @Test
    void optionBracedFixtureExecutesWithExactOutputs() {
        assertEquals("42:0", assertDoesNotThrow(
                () -> executePinnedMain("value-option-braced.ores")));
    }

    @Test
    void proceduralReturnDoesNotReplaceValueExpressionReturn() {
        assertEquals("142:42", assertDoesNotThrow(
                () -> executePinnedMain("value-vs-procedural.ores")));
    }

    /**
     * The canonical guarded-match fixture deliberately has no main().
     * Preserve its exact SHA-pinned source as the prefix, then append a
     * small runtime entrypoint purely for this supplemental behavior test.
     * This must not be recorded as "execution of exact fixture bytes alone":
     * it is execution of an unchanged canonical fixture plus a harness.
     */
    @Test
    void guardedOverEndFixtureExecutesBothGuardAndFallback() {
        assertEquals("42:0:0:2", assertDoesNotThrow(() -> {
            String source = readPinned("value-guard-over-end.ores");
            String harness = """
                
                pub fnc main(): void {
                    stdio.stdout.write(compute(41));
                    stdio.stdout.write(":");
                    stdio.stdout.write(compute(0));
                    stdio.stdout.write(":");
                    stdio.stdout.write(compute(-3));
                    stdio.stdout.write(":");
                    stdio.stdout.write(compute(1));
                    return;
                }
                """;
            return executeCheckedProgram(source + "\n" + harness,
                    "guarded-value-match-with-runtime-harness.ores");
        }));
    }
}
