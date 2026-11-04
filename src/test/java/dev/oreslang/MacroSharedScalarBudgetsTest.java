package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** Exact shared stage-0 admission limits: 8 declarations, 128 expansions. */
final class MacroSharedScalarBudgetsTest {
    private static final String IDENTITY = """
            define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                return value;
            }
            """;

    private static String main(String expression) {
        return "fnc main() -> int { return " + expression + "; }";
    }

    @Test
    void eightDeclarationsAcceptedNineRejectedBeforeExecutableAnalysis() {
        StringBuilder declarations = new StringBuilder();
        for (int i = 0; i < 8; ++i) {
            declarations.append("define macro ident_").append(i)
                    .append("(Syntax<Expr> value) -> Syntax<Expr> { return value; }\n");
        }
        Ast.Program accepted = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck(declarations + main("42")));
        assertTrue(accepted.modules().stream()
                        .flatMap(module -> module.declarations().stream())
                        .noneMatch(Ast.MacroDecl.class::isInstance),
                "all eight checked macro declarations must be erased from runtime AST");

        declarations.append("define macro ident_8(Syntax<Expr> value) -> Syntax<Expr> "
                + "{ return value; }\n");
        IllegalArgumentException denial = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(declarations + main("42")));
        assertTrue(denial.getMessage().contains("macro declaration count exceeds 8"),
                denial::toString);
        assertTrue(denial.getMessage().contains("runtime fallback is forbidden"),
                denial::toString);
    }

    @Test
    void exactly128ExpansionsAccepted129thRejected() {
        String use = "identity!(syntax expr 1)";
        String acceptedExpr = String.join(" + ", Collections.nCopies(128, use));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                IDENTITY + main(acceptedExpr)));
        IllegalArgumentException denial = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        IDENTITY + main(acceptedExpr + " + " + use)));
        assertTrue(denial.getMessage().contains("macro expansion count exceeds 128"),
                denial::toString);
        assertTrue(denial.getMessage().contains("runtime fallback is forbidden"),
                denial::toString);
    }
}
