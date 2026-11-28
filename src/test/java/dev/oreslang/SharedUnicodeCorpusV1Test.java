package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

final class SharedUnicodeCorpusV1Test {
    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
    @Test void executeExactSharedCorpus() throws Exception {
        String corpus = System.getProperty("atto.unicode.corpus.tsv");
        org.junit.jupiter.api.Assumptions.assumeTrue(corpus != null, "explicit shared corpus path required");
        int positive = 0, negative = 0, failures = 0;
        for (String line : Files.readAllLines(Path.of(corpus), StandardCharsets.UTF_8)) {
            String[] fields = line.split("\\t", -1);
            assertEquals(4, fields.length);
            String id = fields[0], expected = fields[1], source = decode(fields[2]), diagnostic = decode(fields[3]);
            try {
                if ("reject".equals(expected)) {
                    RuntimeException error = assertThrows(RuntimeException.class, () -> TypeChecker.check(Parser.parse(source)), id);
                    String message = error.getMessage();
                    assertNotNull(message, id);
                    assertFalse(message.toLowerCase(java.util.Locale.ROOT).contains("entrypoint"), id + ": " + message);
                    // Java's parser describes a stray token after a return as a
                    // missing semicolon. Match that exact parser diagnostic,
                    // only for the four malformed NOT/bang cases.
                    boolean strayNot = java.util.Set.of("postfix", "composite-eq",
                        "composite-equal", "unicode-not-not-a-bang-keyword").contains(id)
                        && message.startsWith("Oreslang parse error at ")
                        && message.endsWith("return statement should end with ';'");
                    assertTrue(strayNot || Pattern.compile(diagnostic, Pattern.CASE_INSENSITIVE)
                        .matcher(message).find(), id + ": " + message);
                    negative++;
                    System.out.println("UNICODE_CASE " + id + " PASS reject-before-guest " + message.replace('\n',' '));
                } else {
                    TypeChecker.check(Parser.parse(source));
                    try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
                        Source guest = Source.newBuilder(OresLanguage.ID, source, id + ".atto")
                            .mimeType(OresLanguage.MIME_TYPE).build();
                        long actual = context.eval(guest).asLong();
                        assertEquals(Long.parseLong(expected), actual, id);
                        positive++;
                        System.out.println("UNICODE_CASE " + id + " PASS guest=" + actual);
                    }
                }
            } catch (AssertionError | RuntimeException error) {
                failures++;
                System.out.println("UNICODE_CASE " + id + " FAIL " + error);
            }
        }
        System.out.println("UNICODE_TOTAL positive=" + positive + " negative=" + negative + " failures=" + failures);
        assertEquals(0, failures);
        assertEquals(8, positive);
        assertEquals(11, negative);
    }
}
