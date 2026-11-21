package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.List;

/**
 * Run the production ReturnCleanupChecker over real parsed using AST nodes.
 * This is not a guest execution or evidence of enabled telemetry imports.
 */
public final class TelemetryScopedControlFlowSmoke {
    private static int checks;

    private static void check(boolean expected, String why) {
        if (!expected) throw new AssertionError(why);
        checks++;
    }

    private static Ast.FunctionDecl function(String body) {
        Ast.Program p = Parser.parse("fnc run(): void {\n" + body + "\n}\n");
        return (Ast.FunctionDecl) p.modules().getFirst().declarations().getFirst();
    }

    private static Ast.Program wrap(Ast.FunctionDecl fn, List<Ast.Stmt> body) {
        Ast.FunctionDecl adjusted = new Ast.FunctionDecl(
                fn.name(), fn.kind(), fn.visibility(), fn.async(),
                fn.suspends(), fn.generator(), fn.structural(),
                fn.nonLexical(), fn.trapped(), fn.actorKind(),
                fn.genericParameters(), fn.parameters(), fn.returnType(),
                fn.annotations(), body, fn.genericSpecs(), fn.effects());
        return new Ast.Program(List.of(new Ast.ModuleDecl("safety", List.of(adjusted))));
    }

    private static void requireFinallyRejection(Ast.FunctionDecl fn) {
        Ast.Program invalid = wrap(fn, List.of(
                new Ast.TryStmt(List.of(), null, List.of(), fn.body())));
        try {
            ReturnCleanupChecker.check(invalid);
            throw new AssertionError("return from finally incorrectly accepted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("return is not allowed inside finally"),
                    "return guard must traverse scoped using body");
        }
    }

    public static void main(String[] ignored) {
        Ast.FunctionDecl trace = function(
                "using telemetry.span(\"payment\") as span { return; }");
        check(trace.body().getFirst() instanceof Ast.UsingTelemetrySpanStmt,
                "telemetry parser preserves distinct lexical node");
        check(ReturnCleanupChecker.check(wrap(trace, trace.body())) != null,
                "ordinary return inside a scoped resource is fine");
        requireFinallyRejection(trace);

        Ast.FunctionDecl borrowed = function(
                "using request.borrow() as view { return; }");
        check(borrowed.body().getFirst() instanceof Ast.UsingBorrowStmt,
                "borrow parser preserves distinct loan node");
        requireFinallyRejection(borrowed);

        Ast.FunctionDecl nested = function("""
                using telemetry.span("outer") as outer {
                    using telemetry.span("inner") as inner { return; }
                }
                """);
        check(ReturnCleanupChecker.check(wrap(nested, nested.body())) != null,
                "nested lexical return permitted outside finally");
        requireFinallyRejection(nested);
        check(checks == 7, "smoke check count changed unexpectedly");
        System.out.println("ATTO_TELEMETRY_RETURN_CLEANUP_PASS assertions=" + checks);
    }
}
