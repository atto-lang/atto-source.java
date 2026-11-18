package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-checker regressions (not JVM reflection or a model mock).
 * The Parser requires explicit "block" and "fi" delimiters.
 * No source-level pointer operators, implicit GC or runtime borrow tracking.
 */
final class OwnershipScopedBindingIdentityTest {
    private static void admitted(String source) {
        var ast = assertDoesNotThrow(() -> Parser.parse(source),
                "source must parse before semantic ownership admission");
        assertDoesNotThrow(() -> OwnershipChecker.check(ast),
                "scoped lexical declarations must retain distinct binding identity");
    }

    private static void denied(String source) {
        var ast = assertDoesNotThrow(() -> Parser.parse(source),
                "source must parse before semantic ownership denial");
        var ex = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(ast),
                "one-arm consumption must not be silently forgotten at the CFG join");
        assertTrue(ex.getMessage().contains("ownership") ||
                ex.getMessage().contains("moved") ||
                ex.getMessage().contains("take"),
                "must report ownership/CFG semantics, not parser unsupported syntax: " +
                        ex.getMessage());
    }

    @TestFactory
    Stream<DynamicTest> lexicalShadowDoesNotForgeAllocationAuthority() {
        return IntStream.range(0, 5).mapToObj(i -> DynamicTest.dynamicTest(
                "shadowed-binding-" + i, () -> admitted("""
                        pub fnc main(): int {
                          val int owner = %d;
                          block {
                            val int owner = %d;
                            val int snapshot = rt copy owner;
                          }
                          return rt share owner;
                        }
                        """.formatted(38 + i, 10 + i))));
    }

    @TestFactory
    Stream<DynamicTest> separateConditionalLocalsRetainCommonOuterOwner() {
        return IntStream.range(0, 5).mapToObj(i -> DynamicTest.dynamicTest(
                "branch-local-" + i, () -> admitted("""
                        pub fnc main(): int {
                          val int owner = %d;
                          if true {
                            val int scratch = rt copy owner;
                          } else {
                            val int scratch = rt copy owner;
                          } fi
                          return rt share owner;
                        }
                        """.formatted(38 + i))));
    }

    @TestFactory
    Stream<DynamicTest> oneBranchMoveMustNotDisappearAtCfgJoin() {
        return IntStream.range(0, 5).mapToObj(i -> DynamicTest.dynamicTest(
                "branch-move-" + i, () -> denied("""
                        pub fnc main(): int {
                          val int owner = %d;
                          if true {
                            val int moved = rt take owner;
                          } else {
                            val int untouched = rt copy owner;
                          } fi
                          return rt share owner;
                        }
                        """.formatted(38 + i))));
    }
}
