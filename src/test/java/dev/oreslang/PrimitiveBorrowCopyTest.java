package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PrimitiveBorrowCopyTest {
    @Test void copiedIntegerLoanIsADetachedNumericValue() {
        String source = """
                fnc read(borrow int n): int { return (rt copy n) + 2; }
                pub fnc main(): int {
                  val n: int = 40;
                  return read(rt borrow n);
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        try (Context context = Context.newBuilder(OresLanguage.ID).build()) {
            assertEquals(42, context.eval(OresLanguage.ID, source).asInt());
        }
    }
    @Test void copiedBooleanLoanCanControlABranch() {
        String source = """
                fnc read(borrow bool flag): int {
                  if rt copy flag; then return 42; fi
                  return 1;
                }
                pub fnc main(): int {
                  val flag: bool = true;
                  return read(rt borrow flag);
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        try (Context context = Context.newBuilder(OresLanguage.ID).build()) {
            assertEquals(42, context.eval(OresLanguage.ID, source).asInt());
        }
    }
    @Test void copyDoesNotEraseContainerLoanCapabilities() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc unsafe(borrow Array<int> values): Array<int> {
                  return rt copy values;
                }
                """)));
    }
    @Test void takeAndShareDoNotErasePrimitiveLoanCapabilities() {
        for (String operation : new String[]{"take", "share"}) {
            String source = "fnc unsafe(borrow int value): int { return rt " + operation + " value; }";
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)), operation);
        }
    }
}
