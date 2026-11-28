package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real parser + typechecker admission for staged core/alloc/std .atto files.
 * This test does NOT claim compiled guest execution, native AOT or backend parity.
 * Negative cases must parse successfully and then fail semantically.
 */
final class AttoCoreAllocCollectionsBorrowAdmissionTest {
    private static String fixture(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException exception) {
            throw new AssertionError("missing exact .atto fixture " + path, exception);
        }
    }

    @Test
    void coreAllocAndCollectionsModulesRequireRealCompilerAdmission() {
        for (String path : List.of(
                "stdlib/core/convert.atto",
                "stdlib/core/fmt.atto",
                "stdlib/alloc/text_convert.atto",
                "stdlib/alloc/text_fmt.atto",
                "stdlib/std/collections.atto")) {
            String source = fixture(path);
            assertDoesNotThrow(() -> Parser.parse(source),
                    "parser must accept staged source, not just host regex: " + path);
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source),
                    "type checker must accept staged source: " + path);
        }
    }

    @Test
    void borrowedReaderReturnsDistinctOwnedArray() {
        String source = fixture("tests/fixtures/core-alloc-ownership/positive_owned_copy.atto");
        assertDoesNotThrow(() -> Parser.parse(source), "positive fixture must parse");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source),
                "positive fixture must pass semantic ownership admission");
    }

    @Test
    void mutationAndBorrowEscapesNeedSemanticRejectionNotSyntaxErrors() {
        for (String path : List.of(
                "tests/fixtures/core-alloc-ownership/negative_mutate_borrow.atto",
                "tests/fixtures/core-alloc-ownership/negative_borrow_escape.atto",
                "tests/fixtures/core-alloc-ownership/negative_result_escape.atto",
                "tests/fixtures/core-alloc-ownership/negative_take_borrow.atto")) {
            String source = fixture(path);
            assertDoesNotThrow(() -> Parser.parse(source),
                    "parser failure is not borrow-checking evidence: " + path);
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source),
                    "an invalid ownership operation must be rejected: " + path);
            String detail = rejected.getMessage() == null ? "" :
                    rejected.getMessage().toLowerCase(Locale.ROOT);
            assertTrue(detail.contains("borrow") || detail.contains("loan")
                            || detail.contains("mutab") || detail.contains("ownership")
                            || detail.contains("owned") || detail.contains("move")
                            || detail.contains("take") || detail.contains("readonly"),
                    "need semantic ownership diagnostic, not unrelated rejection: " +
                            path + " => " + detail);
        }
    }
}
