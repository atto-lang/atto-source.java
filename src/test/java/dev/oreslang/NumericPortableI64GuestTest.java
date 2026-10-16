package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Execute the seven portable signed-i64 source controls against the actual
 * Java/GraalVM guest parser/runtime. Fixture identity is separately pinned by
 * test_numeric_source_i64_cases.py; no Python oracle pass substitutes for this.
 */
final class NumericPortableI64GuestTest {
    private record Case(String expression, long expected) { }

    @Test
    void signedI64OverflowDoesNotSilentlyWrap() throws Exception {
        // Bind these four executable guest negatives to the exact cross-
        // backend corpus. A source change must not silently retain green
        // tests for a stale, independently embedded list.
        byte[] overflowFixture = Files.readAllBytes(
                Path.of("contracts/v1/numeric-parity/source-i64-overflow-cases.json"));
        MessageDigest overflowHash = MessageDigest.getInstance("SHA-1");
        overflowHash.update(("blob " + overflowFixture.length + "\0")
                .getBytes(StandardCharsets.US_ASCII));
        overflowHash.update(overflowFixture);
        assertEquals("2ce0b0ff5c52e54a788280fd64d67fa8dc32614a",
                HexFormat.of().formatHex(overflowHash.digest()),
                "shared signed-i64 overflow fixture drift");
        // These expressions are valid Oreslang syntax but their mathematical
        // result exceeds signed i64. Failure must be attributable to the
        // numeric boundary, rather than an unrelated parser/grammar problem.
        String[] invalid = {
            "9223372036854775807+1",
            "-9223372036854775808-1",
            "3037000500*3037000500",
            "-(-9223372036854775808)"
        };
        for (String expression : invalid) {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                String source = "pub routine main(): int { return "
                        + expression + "; }";
                PolyglotException error = assertThrows(PolyglotException.class,
                        () -> context.eval(OresLanguage.ID, source),
                        "guest silently admitted overflow: " + expression);
                assertTrue(error.getMessage() != null
                                && error.getMessage().toLowerCase(
                                        java.util.Locale.ROOT).contains("overflow"),
                        "unexpected rejection reason for " + expression
                                + ": " + error.getMessage());
            }
        }
    }

    @Test
    void portableI64InputsReturnFullWidthSignedResults() throws Exception {
        // This guest suite currently embeds source expressions. Fail if the
        // shared corpus changes without an explicit guest-case code review;
        // the Python oracle alone is insufficient to bind guest execution.
        byte[] fixture = Files.readAllBytes(
                Path.of("contracts/v1/numeric-parity/source-i64-cases.json"));
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + fixture.length + "\0").getBytes(StandardCharsets.US_ASCII));
        sha1.update(fixture);
        assertEquals("bbc319c19ecd007109d33592e210091eb267e5a2",
                HexFormat.of().formatHex(sha1.digest()), "numeric source fixture drift");
        Case[] cases = {
            new Case("2+3*4", 14L),
            new Case("(2+3)*4", 20L),
            new Case("(8-3)*5+2", 27L),
            new Case("100-(3+2)-19", 76L),
            new Case("3*4-2*5-7", -5L),
            new Case("9223372036854775807-1", Long.MAX_VALUE-1),
            new Case("-9223372036854775808", Long.MIN_VALUE)
        };
        for (Case test : cases) {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                String program = "pub routine main(): int { return "
                        + test.expression() + "; }";
                assertEquals(test.expected(),
                        context.eval(OresLanguage.ID, program).asLong(),
                        "portable signed-i64 source " + test.expression());
            }
        }
    }
}
