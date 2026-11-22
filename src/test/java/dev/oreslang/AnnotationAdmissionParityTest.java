package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-backend annotation admission floor; intentionally not a feature-parity claim. */
final class AnnotationAdmissionParityTest {
    @Test
    void checksCanonicalAnnotationAdmissionVectors() throws Exception {
        Path corpus = Path.of("contracts/v1/annotation-admission-v1.tsv");
        assertTrue(Files.isRegularFile(corpus), "missing shared annotation admission corpus");
        Set<String> identifiers = new HashSet<>();
        int exercised = 0;
        for (String line : Files.readAllLines(corpus)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\\t", -1);
            assertEquals(3, fields.length, "malformed annotation admission TSV row");
            String kind = fields[0], id = fields[1], source = fields[2].replace("\\n", "\n");
            assertFalse(id.isBlank());
            assertTrue(identifiers.add(id), "duplicate annotation case " + id);
            assertFalse(source.isBlank());
            switch (kind) {
                case "shared-deny" -> assertThrows(IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse(source)), id);
                case "shared-allow" -> assertDoesNotThrow(
                        () -> TypeChecker.check(Parser.parse(source)), id);
                case "bootstrap-deny" -> { /* Java's metadata-only subset differs from scalar bootstraps. */ }
                default -> fail("unknown annotation admission class: " + kind);
            }
            exercised++;
        }
        assertTrue(exercised >= 12, "incomplete annotation admission corpus");
    }
}
