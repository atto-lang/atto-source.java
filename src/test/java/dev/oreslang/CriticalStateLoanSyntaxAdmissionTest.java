package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser-only shape parity for actor critical state loans. No test in this
 * class asserts runtime admission, state isolation, or executable loan safety.
 */
final class CriticalStateLoanSyntaxAdmissionTest {

    private static String actorWith(String argument) {
        return """
                define actor Ledger as
                  pub change(): void {
                    self.handle_state(%s);
                    return;
                  }
                end
                """.formatted(argument);
    }

    private static Ast.LambdaExpr callback(String mode) {
        var parsed = Parser.parse(actorWith(mode + " |s| -> { return; }"));
        Ast.ClassDecl actor = (Ast.ClassDecl) parsed.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.ClassDecl klass
                        && klass.name().equals("Ledger"))
                .findFirst().orElseThrow();
        var stmt = assertInstanceOf(Ast.ExprStmt.class,
                actor.methods().getFirst().body().getFirst());
        var call = assertInstanceOf(Ast.CallExpr.class, stmt.expression());
        assertEquals(1, call.arguments().size());
        return assertInstanceOf(Ast.LambdaExpr.class, call.arguments().getFirst());
    }

    private static void rejected(String argument, String message) {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(actorWith(argument)));
        assertTrue(ex.getMessage().contains(message), ex::getMessage);
    }

    @Test void aliasesNormalizeToTheSameWriteMode() {
        for (String spelling : new String[] {
                "critical[write]", "critical-write", "critical"
        }) {
            assertEquals(Ast.CriticalLoanMode.WRITE,
                    callback(spelling).criticalLoanMode(), spelling);
        }
    }

    @Test void aliasesNormalizeToTheSameReadMode() {
        for (String spelling : new String[] {
                "critical[read]", "critical-read"
        }) {
            assertEquals(Ast.CriticalLoanMode.READ,
                    callback(spelling).criticalLoanMode(), spelling);
        }
    }

    @Test void rejectsUnknownModesAndMalformedArguments() {
        rejected("critical[upgrade] |s| -> { return; }", "only read or write");
        rejected("critical-upgrade |s| -> { return; }", "only read or write");
        rejected("critical[read,write] |s| -> { return; }", "exactly one");
        rejected("critical[write] value", "inline |state|");
        rejected("critical[write] || -> { return; }", "exactly one named state parameter");
        rejected("critical[write] |s,t| -> { return; }", "exactly one named state parameter");
        rejected("critical[write] |s| -> { return; }, another", "exactly one callback argument");
    }

    @Test void hyphenMustBeImmediatelyAdjacent() {
        rejected("critical -write |s| -> { return; }",
                "adjacent ASCII");
        rejected("critical- write |s| -> { return; }",
                "adjacent ASCII");
    }

    @Test void criticalIndexOutsideIntrinsicIsNotLoanSyntax() {
        var program = Parser.parse("""
                fnc calculate(): int {
                  return critical[write];
                }
                """);
        Ast.FunctionDecl function = (Ast.FunctionDecl) program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.FunctionDecl)
                .findFirst().orElseThrow();
        Ast.ReturnStmt ret = assertInstanceOf(Ast.ReturnStmt.class,
                function.body().getFirst());
        assertInstanceOf(Ast.IndexExpr.class, ret.value());
    }

    @Test void checkedExecutionRemainsDisabledWithoutLoanAndArenaLowering() {
        var program = Parser.parse("""
                define actor Ledger as
                  state { epoch: int = 0; }
                  pub change(): void with state.write {
                    self.handle_state(critical[write] |s| -> {
                      s.epoch = s.epoch + 1;
                    });
                    return;
                  }
                end
                """);
        var ex = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(ex.getMessage().contains("compiler-enforced handle_state loans"),
                ex::getMessage);
    }
}
