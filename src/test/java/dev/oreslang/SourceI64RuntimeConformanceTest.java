package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Execute the same reviewed source-i64 arithmetic inputs in real Truffle guests. */
final class SourceI64RuntimeConformanceTest {
    private static final String REVIEWED_BLOB = "bbc319c19ecd007109d33592e210091eb267e5a2";
    private static final String REVIEWED_SHA256 = "39bddbe558ad1c336271f91e6403c4555f2f16a7ed33a3355ee897721b6ab02a";
    private static final List<String> IDS = List.of(
            "precedence", "grouping", "signed-multiplication", "subtraction",
            "negative-result", "max-minus-one", "signed-minimum");
    // Safe because the entire fixture is pinned byte-for-byte before extraction.
    // Case strings cannot contain JSON escapes in the reviewed snapshot.
    private static final Pattern CASE = Pattern.compile(
            "\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*" +
            "\"expression\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*" +
            "\"expected\"\\s*:\\s*\"(-?[0-9]+)\"\\s*\\}");

    private record Case(String id, String expression, String expected) { }

    private static List<Case> cases() throws Exception {
        byte[] bytes;
        try (InputStream input = SourceI64RuntimeConformanceTest.class.getResourceAsStream(
                "/parity/portable-i64.v1.json")) {
            assertNotNull(input, "canonical source-i64 corpus is missing");
            bytes = input.readAllBytes();
        }
        assertTrue(bytes.length > 0 && bytes.length <= 8192, "corpus size limit");
        ByteArrayOutputStream gitBlob = new ByteArrayOutputStream();
        gitBlob.writeBytes(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        gitBlob.writeBytes(bytes);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(gitBlob.toByteArray()));
        assertEquals(REVIEWED_BLOB, sha, "source-i64 fixture differs from reviewed Git blob");
        assertEquals(REVIEWED_SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(bytes)), "source-i64 fixture differs from SHA-256 pin");

        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"reference-only-not-compiled-execution\""),
                "fixture cannot attest execution without a real backend");
        Matcher matcher = CASE.matcher(json);
        ArrayList<Case> parsed = new ArrayList<>();
        HashSet<String> found = new HashSet<>();
        while (matcher.find()) {
            assertTrue(found.add(matcher.group(1)), "duplicate source-i64 case ID");
            parsed.add(new Case(matcher.group(1), matcher.group(2), matcher.group(3)));
        }
        assertEquals(IDS, parsed.stream().map(Case::id).toList(),
                "case count or ordering changed");
        return parsed;
    }

    @TestFactory
    Stream<DynamicTest> realGraalGuestArithmetic() throws Exception {
        return cases().stream().map(test -> DynamicTest.dynamicTest(test.id(), () -> {
            String program = "pub fnc main():void { stdio.stdout.write(" +
                    test.expression() + "); return; }\n";
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)), program);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            Source compiled = Source.newBuilder(OresLanguage.ID, program,
                    "portable-i64-" + test.id() + ".ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(stdout).build()) {
                context.eval(compiled);
            }
            assertEquals(test.expected(), stdout.toString(StandardCharsets.UTF_8),
                    "actual Truffle output differs from the pinned expected signed i64");
        }));
    }


    // Candidate scalar intersection with LLVM, Wasm, Cranelift and .NET.
    // These dynamically named tests execute real Java/Graal Oreslang guest code.
    private static final List<Case> WAVE_SCALARS = List.of(
            new Case("34", "(2 + 3) * 8 + 2", "42"),
            new Case("35", "-(-42)", "42"),
            new Case("36", "(100 - 16) - 42", "42"),
            new Case("37", "-7 * -6", "42"),
            new Case("38", "(7 - 14) * -6", "42"),
            new Case("39", "9223372036854775807 - 9223372036854775765", "42"),
            new Case("40", "-9223372036854775808 + 9223372036854775807 + 43", "42"),
            new Case("41", "2 * (3 + 4) * 3", "42"));

    // SHA-256 of the exact Oreslang corpus files programs/34..41, including
    // comments and LF. This rejects backend-specific source rewrites.
    private static final List<String> WAVE_SOURCE_SHA256 = List.of(
            "2d186d0c400c23c6fcdf1df963580351eacea82250097a1cd083a1cc308b40f5",
            "73e5fc703df0b0d3af6898c156ff105ec28e8dfc639f2a02ccca0f1cd3c69dd8",
            "2ca62fbe11d05cb219a4be255669842994c279e0e0a5208fbc3d3a0e4c047d29",
            "efb740b31d72bfdc1eecc8a7eb87a7b84c8961f444fbbcc08a142ca54cf7c97b",
            "391467d7baf7b84b339a63e55be2395d9a8a4d92b5719f249df428b34bac7ccd",
            "d1fa2dc614cd61c442ab1bfa5683fac50cc9d72139e0dcdf0a67b3610d427bd6",
            "0322dc2a7d48fed12d917aaaf2a50c025b77402a2b21fdbb87224fece59b44b0",
            "3483cb15d4b2f2c24f90ba65e55e886b59eaea2190aa9c18f8ed10badba04e76");

    @TestFactory
    Stream<DynamicTest> sharedSignedI64WaveExecutesInRealGraalGuest() {
        return WAVE_SCALARS.stream().map(test -> DynamicTest.dynamicTest(
                "parity-wave-" + test.id(), () -> {
                    String program = "// " + test.id()
                            + ": candidate source-identical checked-i64 scalar program.\n"
                            + "// Expected integer result 42; guest execution on each backend is unverified.\n"
                            + "pub fnc main(): int { return " + test.expression() + "; }\n";
                    String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(program.getBytes(StandardCharsets.UTF_8)));
                    int index = Integer.parseInt(test.id()) - 34;
                    assertEquals(WAVE_SOURCE_SHA256.get(index), sha256,
                            "Oreslang source bytes must equal the canonical parity fixture");
                    assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)),
                            "Graal source rejection: " + test.id());
                    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                    Source compiled = Source.newBuilder(OresLanguage.ID, program,
                            "scalar-wave-" + test.id() + ".ores")
                            .mimeType(OresLanguage.MIME_TYPE).build();
                    try (Context context = Context.newBuilder(OresLanguage.ID)
                            .allowAllAccess(false).out(stdout).build()) {
                        Value result = context.eval(compiled);
                        assertTrue(result.fitsInLong(),
                                "real Graal guest must return a signed-i64 value");
                        assertEquals(Long.parseLong(test.expected()), result.asLong(),
                                "real Graal guest scalar mismatch: " + test.id());
                    }
                    assertEquals("", stdout.toString(StandardCharsets.UTF_8),
                            "unexpected guest stdout from pure scalar program");
                }));
    }

    @Test
    void cannotLowerFloatDivisionOrOverflowLiteralAsPortableI64() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(
                Parser.parse("pub fnc main():int{return 3/2;}")));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("pub fnc main():int{return 9223372036854775808;}"));
    }
}
