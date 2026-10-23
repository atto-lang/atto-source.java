package dev.oreslang.parser;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.TypeChecker;

/** Frontend boundaries only; no guest runtime or scheduler is executed here. */
public final class FuturePipelineTypingCheck {
    private FuturePipelineTypingCheck() { }

    public static void main(String[] args) {
        check();
        System.out.println("restricted pipeline typing boundary checks passed (frontend only).");
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

        // A restricted, pure scalar chain is checked as Future<int> and
        // produces immutable identity-bound lowering proofs at both stages.
        Ast.Program lifted = TypeChecker.check(Parser.parse("""
                async fnc source(): int { return 21; }
                fnc save(int value): int { return value + 1; }
                fnc twice(int value): int { return value * 2; }
                fnc main(): void {
                  val Future<int> pending = source() |> save() |> twice();
                  val Option<int> result = trap await (pending);
                  return;
                }
                """));
        Ast.FunctionDecl liftedMain = (Ast.FunctionDecl)
                lifted.modules().get(0).declarations().get(3);
        Ast.BindingStmt pending = (Ast.BindingStmt) liftedMain.body().get(0);
        Ast.CallExpr second = (Ast.CallExpr) pending.initializer();
        Ast.CallExpr first = (Ast.CallExpr) second.arguments().getFirst();
        if (!Ast.CheckedPureFuturePipe.lookup(first)
                || !Ast.CheckedPureFuturePipe.lookup(second)) {
            throw new AssertionError("checked Future chain lost a stage proof");
        }
        if (Ast.CheckedPureFuturePipe.target(first)
                != lifted.modules().get(0).declarations().get(1)
                || Ast.CheckedPureFuturePipe.target(second)
                != lifted.modules().get(0).declarations().get(2)) {
            throw new AssertionError("checked Future stage bound to the wrong declaration identity");
        }
        if (Ast.CheckedPureFuturePipe.lookup(
                second.rewrite(second.callee(), second.typeArguments(),
                        second.typeArgumentsPresent(), second.arguments()))) {
            throw new AssertionError("rewritten Future stage inherited stale lowering proof");
        }

        rejected("""
                async fnc source(): int { return 21; }
                fnc save(int value, int extra): int { return value + extra; }
                fnc main(): void {
                  val Future<int> pending = source() |> save(5);
                  return;
                }
                """, "named unary stage with no extra arguments");

        rejected("""
                async fnc source(): int { return 21; }
                fnc save(int value): int {
                  let int other = value;
                  return other;
                }
                fnc main(): void {
                  val Future<int> pending = source() |> save();
                  return;
                }
                """, "pure int -> int stage");

        TypeChecker.check(Parser.parse("""
                async fnc source(): int { return 21; }
                fnc main(): void {
                  val Option<int> result = trap await (source());
                  return;
                }
                """));

        rejected("""
                async fnc source(): int { return 21; }
                fnc main(): void {
                  val Option<int> result = await trap (source());
                  return;
                }
                """, "await requires Future<T> or ActorRef<T>", "name=Option");
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

