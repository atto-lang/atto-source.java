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
        if (!TypeChecker.CheckedPureFuturePipe.lookup(first)
                || !TypeChecker.CheckedPureFuturePipe.lookup(second)) {
            throw new AssertionError("checked Future chain lost a stage proof");
        }
        if (TypeChecker.CheckedPureFuturePipe.target(first)
                != lifted.modules().get(0).declarations().get(1)
                || TypeChecker.CheckedPureFuturePipe.target(second)
                != lifted.modules().get(0).declarations().get(2)) {
            throw new AssertionError("checked Future stage bound to the wrong declaration identity");
        }
        // A later bad declaration must invalidate the entire compilation
        // rather than leak a proof for an earlier successfully visited pipe.
        Ast.Program invalidWholeProgram = Parser.parse("""
                async fnc source(): int { return 21; }
                fnc save(int value): int { return value + 1; }
                fnc main(): void {
                  val Future<int> pending = source() |> save();
                  return;
                }
                fnc invalid(): bool { return 123; }
                """);
        Ast.FunctionDecl partlyCheckedMain = (Ast.FunctionDecl)
                invalidWholeProgram.modules().getFirst().declarations().get(2);
        Ast.CallExpr unsafeEarlierPipe = (Ast.CallExpr)
                ((Ast.BindingStmt) partlyCheckedMain.body().getFirst()).initializer();
        try {
            TypeChecker.checkTypes(invalidWholeProgram);
            throw new AssertionError("later invalid declaration unexpectedly type-checked");
        } catch (IllegalArgumentException expected) {
            if (TypeChecker.CheckedPureFuturePipe.lookup(unsafeEarlierPipe)) {
                throw new AssertionError(
                        "failed compilation published a partially checked Future proof");
            }
        }

        // Host code must not have a public proof-minting capability,
        // even if it constructs a matching function declaration and CallExpr.
        try {
            TypeChecker.CheckedPureFuturePipe.class.getMethod(
                    "record", Ast.CallExpr.class, Ast.FunctionDecl.class);
            throw new AssertionError("pipeline proof minting is publicly accessible");
        } catch (NoSuchMethodException expected) {
            // The compiler is the only permitted issuer.
        }

        if (TypeChecker.CheckedPureFuturePipe.lookup(
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

        // Scalar call-shaped producers do not incur speculative Future
        // type checking or acquire a checked Future pipeline proof.
        TypeChecker.check(Parser.parse("""
                fnc source(): int { return 21; }
                fnc save(int value): int { return value + 1; }
                fnc main(): int { return source() |> save(); }
                """));

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

