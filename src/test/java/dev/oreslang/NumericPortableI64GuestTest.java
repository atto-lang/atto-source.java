package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Execute the seven portable signed-i64 source controls against the actual
 * Java/GraalVM guest parser/runtime. Fixture identity is separately pinned by
 * test_numeric_source_i64_cases.py; no Python oracle pass substitutes for this.
 */
final class NumericPortableI64GuestTest {
    private record Case(String expression, long expected) { }

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
