package dev.oreslang.parser;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.TypeChecker;

/** Frontend boundaries only; no guest runtime or scheduler is executed here. */
public final class FuturePipelineTypingCheck {
    private FuturePipelineTypingCheck() { }

    public static void main(String[] args) {
        check();
        System.out.println("5 pipeline typing boundary checks passed (frontend only).");
    }

    static void check() {
        Ast.Program checked = TypeChecker.check(Parser.parse("""
                fnc step(int value): int { return value + 1; }
                fnc main(): int { return 2 |> step; }
                """));
        Ast.FunctionDecl main = (Ast.FunctionDecl) checked.modules().get(0).declarations().get(1);
        Ast.CallExpr call = (Ast.CallExpr) ((Ast.ReturnStmt) main.body().get(0)).value();
        if (call.origin() != Ast.CallOrigin.VALUE_PIPE_ARGUMENT)
            throw new AssertionError("typing erased pipeline provenance");

        TypeChecker.check(Parser.parse("""
                async fnc source(): int { return 21; }
                fnc observe(Future<int> pending): int { return 1; }
                fnc main(): int { return observe(source()); }
                """));

        rejected("""
                async fnc source(): int { return 21; }
                fnc save(int value): int { return value + 1; }
                fnc main(): void {
                  val Result<int, Error> result = trap await (source() |> save());
                  return;
                }
                """, "argument 1 has type Named[name=Future", "but expected INT");

        TypeChecker.check(Parser.parse("""
                async fnc source(): int { return 21; }
                fnc main(): void {
                  val Result<int, Error> result = trap await (source());
                  return;
                }
                """));

        rejected("""
                async fnc source(): int { return 21; }
                fnc main(): void {
                  val Result<int, Error> result = await trap (source());
                  return;
                }
                """, "await requires Future<T> or ActorRef<T>", "name=Result");
    }

    private static void rejected(String source, String... diagnosticFragments) {
        Ast.Program parsed = Parser.parse(source); // parser rejection is not a typing pass
        try {
            TypeChecker.check(parsed);
        } catch (IllegalArgumentException expected) {
            for (String fragment : diagnosticFragments) {
                if (expected.getMessage() == null || !expected.getMessage().contains(fragment))
                    throw new AssertionError("wrong typing diagnostic: " + expected.getMessage(), expected);
            }
            return;
        }
        throw new AssertionError("unsupported source was admitted: " + source);
    }
}

