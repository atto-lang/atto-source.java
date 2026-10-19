package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
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

    @Test
    void cannotLowerFloatDivisionOrOverflowLiteralAsPortableI64() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(
                Parser.parse("pub fnc main():int{return 3/2;}")));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("pub fnc main():int{return 9223372036854775808;}"));
    }
}
