package dev.oreslang;

import dev.oreslang.compiler.V3ScalarDefaultProgramNormalizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Guard zero supplied arguments against invalid empty staged call ASTs. */
final class V3ZeroArgumentCallRegressionTest {
    @Test
    void defaultedScalarZeroArgumentCallProducesFullArityLiteral() {
        var normalized = V3ScalarDefaultProgramNormalizer.parseNormalizeAndAnalyze("""
                pub fnc choose(@Default(7) int value): int { return value; }
                pub fnc main(): int { return choose(); }
                """);
        var main = (dev.oreslang.ast.Ast.FunctionDecl)
                normalized.modules().getFirst().declarations().get(1);
        var call = (dev.oreslang.ast.Ast.CallExpr)
                ((dev.oreslang.ast.Ast.ReturnStmt) main.body().getFirst()).value();
        assertEquals(1, call.arguments().size());
        assertEquals(7L, ((dev.oreslang.ast.Ast.LiteralExpr)call.arguments().getFirst()).value());
    }

    @Test
    void plainZeroParameterDirectFunctionDoesNotBecomeStaged() {
        assertDoesNotThrow(() -> V3ScalarDefaultProgramNormalizer.parseNormalizeAndAnalyze("""
                pub fnc answer(): int { return 42; }
                pub fnc main(): int { return answer(); }
                """));
    }

    @Test
    void ordinaryPositionalLiteralIsNormalized() {
        var p = V3ScalarDefaultProgramNormalizer.parseNormalizeAndAnalyze("""
                pub fnc value(int a): int { return a; }
                pub fnc main(): int { return value(9); }
                """);
        var fn = (dev.oreslang.ast.Ast.FunctionDecl)p.modules().getFirst().declarations().get(1);
        var call = (dev.oreslang.ast.Ast.CallExpr)
                ((dev.oreslang.ast.Ast.ReturnStmt)fn.body().getFirst()).value();
        assertEquals(9L, ((dev.oreslang.ast.Ast.LiteralExpr)call.arguments().getFirst()).value());
    }

    @Test
    void extraPositionalLiteralsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> V3ScalarDefaultProgramNormalizer.parseNormalizeAndAnalyze("""
                        pub fnc value(int a): int { return a; }
                        pub fnc main(): int { return value(1, 2); }
                        """));
    }

    @Test
    void requiredParameterCannotBeOmittedByZeroArgumentDirectCall() {
        assertThrows(IllegalArgumentException.class,
                () -> V3ScalarDefaultProgramNormalizer.parseNormalizeAndAnalyze("""
                        pub fnc answer(int value): int { return value; }
                        pub fnc main(): int { return answer(); }
                        """));
    }

    @Test
    void omittedOptionWithoutDefaultProducesCheckedNone() {
        assertDoesNotThrow(() -> V3ScalarDefaultProgramNormalizer.parseNormalizeAndAnalyze("""
                pub fnc empty(Option<int> value?): bool { return value.is_none(); }
                pub fnc main(): bool { return empty(); }
                """));
    }
}
