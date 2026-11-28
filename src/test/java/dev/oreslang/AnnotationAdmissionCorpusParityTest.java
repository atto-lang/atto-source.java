package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage-specific, byte-identical cross-backend contract. Java validates
 * source parser admission; this test does NOT certify AOT execution.
 */
final class AnnotationAdmissionCorpusParityTest {
    @Test
    void sharedAnnotationsHaveExplicitJavaParserOutcomes() throws Exception {
        Path corpus = Path.of("contracts", "v1", "annotation-admission-v1.cases");
        List<String> lines = Files.readAllLines(corpus, StandardCharsets.UTF_8);
        Set<String> names = new HashSet<>();
        int passes = 0, rejections = 0;
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\\|", -1);
            assertEquals(7, fields.length, "corpus row schema: " + line);
            String id = fields[0];
            assertTrue(id.matches("[a-z][a-z_0-9]*"), id);
            assertTrue(names.add(id), "duplicate vector id " + id);
            for (int i = 1; i <= 5; i++)
                assertTrue(fields[i].equals("pass") || fields[i].equals("reject"),
                        "invalid backend expectation " + id);
            byte[] bytes = Base64.getDecoder().decode(fields[6]);
            String source = new String(bytes, StandardCharsets.UTF_8);
            assertArrayEquals(bytes, source.getBytes(StandardCharsets.UTF_8),
                    "noncanonical source UTF-8: " + id);
            if (fields[1].equals("pass")) {
                passes++;
                assertDoesNotThrow(() -> Parser.parse(source), id);
            } else {
                rejections++;
                IllegalArgumentException ex = assertThrows(
                        IllegalArgumentException.class, () -> Parser.parse(source), id);
                assertFalse(ex.getMessage().isBlank(), "missing diagnostic: " + id);
            }
        }
        assertEquals(17, names.size(), "corpus completeness");
        assertEquals(5, passes, "positive parser controls");
        assertEquals(12, rejections, "negative parser controls");
    }
}
