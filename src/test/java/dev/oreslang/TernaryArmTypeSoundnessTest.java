package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TernaryArmTypeSoundnessTest {
    private static final String CLASSES = """
            define class A as
            end
            define class B as
            end
            """;

    private static void rejected(String source) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        // A parse error would prove nothing about the type rule under test.
        assertFalse(error.getMessage().contains("parse error"), error.getMessage());
    }

    private static void accepted(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void mismatchedArmsCannotPassAsOneArmsType() {
        rejected("fnc f(bool c): int { return c ? 1 : \"wrong\"; }");
        rejected("fnc f(bool c): void { val int x = c ? 1 : \"wrong\"; return; }");
        rejected("fnc g(int a): int { return a; } fnc f(bool c): int { return g(c ? 1 : \"wrong\"); }");
        rejected("fnc f(bool c): int { val x = c ? 1 : \"wrong\"; return x; }");
        rejected("fnc f(bool c): int { return c ? (c ? 1 : \"x\") : 2; }");
        rejected(CLASSES + "fnc f(bool c): A { return c ? new A() : new B(); }");
        // Iterating a mixed tuple had the same Unknown join as a mixed ternary.
        rejected("fnc f(): int {\n  val t = (1, \"wrong\");\n  for val x of t do\n    return x;\n  done\n  return 0;\n}\n");
    }

    @Test
    void compatibleArmsStayAccepted() {
        accepted("fnc f(bool c): int { return c ? 1 : 2; }");
        accepted("fnc f(bool c): String { return c ? \"a\" : \"b\"; }");
        accepted("fnc f(bool c): Option<int> { return c ? Some(1) : None; }");
        accepted("fnc f(bool c): int | String { return c ? 1 : \"x\"; }");
        accepted(CLASSES + "fnc f(bool c): A | B { return c ? new A() : new B(); }");
        accepted("fnc f(): int {\n  val t = (1, 2);\n  for val x of t do\n    return x;\n  done\n  return 0;\n}\n");
    }
}
