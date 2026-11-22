package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Optimizer regression: declared return types are semantic AST contracts.
 * Rewriting, constant substitution and reachability must preserve them.
 */
final class TypedLambdaTreeShakerContractTest {
    private static Ast.FunctionDecl transformedMain(String guest) {
        var checked = TypeChecker.check(Parser.parse(guest));
        var rewritten = TreeShaker.shake(checked, BuildOptions.executable()).program();
        return rewritten.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.FunctionDecl fn && fn.name().equals("main"))
                .map(decl -> (Ast.FunctionDecl) decl)
                .findFirst().orElseThrow();
    }

    private static Ast.LambdaExpr boundLambda(Ast.FunctionDecl main, String name) {
        for (Ast.Stmt stmt : main.body()) {
            if (stmt instanceof Ast.BindingStmt binding && binding.name().equals(name)) {
                return assertInstanceOf(Ast.LambdaExpr.class, binding.initializer());
            }
        }
        throw new AssertionError("tree shaker unexpectedly removed binding " + name);
    }

    private static Ast.LambdaExpr deferredLambda(Ast.FunctionDecl main) {
        for (Ast.Stmt stmt : main.body()) {
            if (stmt instanceof Ast.DeferStmt deferred) {
                return assertInstanceOf(Ast.LambdaExpr.class, deferred.expression());
            }
        }
        throw new AssertionError("tree shaker unexpectedly removed deferred cleanup");
    }

    @Test
    void typedPipeLambdaRetainsReturnContractAfterOptimization() {
        Ast.FunctionDecl main = transformedMain("""
                pub fnc main(): void {
                    const add = |a: int, b: int| -> int { return a + b; };
                    stdio.println(add(19, 23));
                    return;
                }
                """);
        assertEquals("int", boundLambda(main, "add").declaredReturnType().name());
    }

    @Test
    void namedCallableAscriptionPreservesInitializerReturnContract() {
        Ast.FunctionDecl main = transformedMain("""
                pub fnc main(): void {
                    const add2<(int, int) => int> =
                        fnc(a: int, b: int) -> int { return a + b; };
                    stdio.println(add2(19, 23));
                    return;
                }
                """);
        assertEquals("int", boundLambda(main, "add2").declaredReturnType().name());
    }

    @Test
    void explicitDeferVoidSurvivesTreeShaking() {
        Ast.FunctionDecl main = transformedMain("""
                pub fnc main(): void {
                    defer || -> void { stdio.println("cleanup"); };
                    return;
                }
                """);
        assertEquals("void", deferredLambda(main).declaredReturnType().name());
    }

    @Test
    void inferredDeferVoidDoesNotAcquireSpuriousAnnotation() {
        Ast.FunctionDecl main = transformedMain("""
                pub fnc main(): void {
                    defer || -> { stdio.println("cleanup"); };
                    return;
                }
                """);
        assertNull(deferredLambda(main).declaredReturnType());
    }
}
