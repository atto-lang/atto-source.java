package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class StructWithMembersTargetTest {
    private static final String GROUPED = """
            type Point = struct {
                int x;
                int y;
            } with {
                constructor(int x, int y) {
                    self.x = x;
                    self.y = y;
                }
                mtd int • sum() { return self.x + self.y; }
                mtd int <- product() { return self.x * self.y; }
                mtd is_origin() : bool { return self.x == 0 && self.y == 0; }
            }
            """;
    private static final String CHAINED = """
            type Point = struct { int x; int y; }
            with constructor(int x, int y) { self.x = x; self.y = y; }
            with mtd int • sum() { return self.x + self.y; }
            with mtd int <- product() { return self.x * self.y; }
            with mtd is_origin() : bool { return self.x == 0 && self.y == 0; }
            """;
    private static String failure(String source) {
        return assertThrows(IllegalArgumentException.class, () -> Parser.parse(source)).getMessage();
    }

    @Test
    void groupedAndChainedHeadersParseBeforeValueAbiGate() {
        for (String source : new String[]{GROUPED, CHAINED,
                GROUPED.replace("int • sum()", "sum(): int"),
                GROUPED.replace("int <- product()", "int • product()"),
                GROUPED.replace("is_origin() : bool", "bool <- is_origin()")}) {
            assertTrue(failure(source).contains("attached struct members parsed"), source);
        }
    }

    @Test
    void badMarkerAndInvalidMemberNeverReachValueAbiGate() {
        assertFalse(failure(GROUPED.replace("is_origin() : bool", "bool * is_origin()"))
                .contains("attached struct members parsed"));
        assertTrue(failure(GROUPED.replace("constructor(int x, int y)", "print(42); constructor(int x, int y)"))
                .contains("expected attached struct constructor or mtd"));
    }

    @Test
    void duplicateNormalizedSignaturesRejectBeforeValueAbiGate() {
        assertTrue(failure(GROUPED.replace("int <- product()", "int <- sum()"))
                .contains("duplicate attached struct method signature"));
        assertTrue(failure(GROUPED.replace("constructor(int x, int y)",
                "constructor(int a, int b) {} constructor(int x, int y)"))
                .contains("duplicate attached struct constructor signature"));
    }
}
