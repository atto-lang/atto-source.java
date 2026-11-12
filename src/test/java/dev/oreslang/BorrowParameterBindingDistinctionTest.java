package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Mutable bindings and mutable borrowed referents are distinct capabilities. */
final class BorrowParameterBindingDistinctionTest {
    private static final String BOX = """
            define class Box as
              pub let int value = 0;
            end
            """;

    private static void admits(String source) {
        assertDoesNotThrow(() ->
                OwnershipChecker.check(TypeChecker.check(Parser.parse(BOX + source))));
    }

    private static void rejects(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(TypeChecker.check(Parser.parse(BOX + source))));
    }

    @Test
    void exclusiveBorrowMutatesReferentWithoutRebinding() {
        admits("""
                fnc change(borrow mut Box s): void {
                  s.value = s.value + 1;
                  return;
                }
                pub fnc main(): int {
                  val Box box = new Box();
                  change(rt mut borrow box);
                  return box.value;
                }
                """);
        rejects("""
                fnc invalid(borrow mut Box s): void {
                  s = new Box();
                  return;
                }
                """);
    }

    @Test
    void sharedBorrowCannotWriteAndOwnedMutableParameterCanRebind() {
        rejects("""
                fnc invalid(borrow Box s): void {
                  s.value = 1;
                  return;
                }
                """);
        admits("""
                fnc valid(Box mut s): int {
                  s = new Box();
                  return s.value;
                }
                """);
    }

    @Test
    void borrowedParameterCannotAlsoRequestMutableBinding() {
        rejects("""
                fnc invalid(borrow mut Box mut s): void {
                  return;
                }
                """);
        rejects("""
                fnc invalid(borrow Box mut s): void {
                  return;
                }
                """);
    }

    @Test
    void pipeLambdaShorthandsKeepDistinctAstCapabilities() {
        Ast.Program program = Parser.parse("""
                fnc sample(): void {
                  const fnc first = |mut s| -> { return; };
                  const fnc second = |borrow mut s| -> { return; };
                  return;
                }
                """);
        Ast.FunctionDecl function = (Ast.FunctionDecl) program.modules()
                .getFirst().declarations().getFirst();
        Ast.LambdaExpr rebindable = (Ast.LambdaExpr)
                ((Ast.BindingStmt) function.body().get(0)).initializer();
        Ast.LambdaExpr borrowed = (Ast.LambdaExpr)
                ((Ast.BindingStmt) function.body().get(1)).initializer();
        Ast.Param mutableName = rebindable.parameters().getFirst();
        Ast.Param mutableLoan = borrowed.parameters().getFirst();

        assertTrue(mutableName.mutable());
        assertFalse(mutableName.type().isBorrow());
        assertFalse(mutableLoan.mutable());
        assertTrue(mutableLoan.type().isBorrow());
        assertTrue(mutableLoan.type().mutableBorrow());
        assertEquals("$infer$", mutableLoan.type().borrowedTarget().name());
    }

    @Test
    void contextualMutableBorrowCannotBeForgedFromSharedCallbackType() {
        // The checker must not infer an exclusive loan from an expected shared borrow.
        rejects("""
                fnc invalid(): void {
                  val Fnc<borrow Box, void> handler =
                    |borrow mut s| -> void { return; };
                  return;
                }
                """);
    }

    @Test
    void typedCallbackCanInferBorrowedReferentWithoutMakingBindingReassignable() {
        admits("""
                fnc valid(): void {
                  val Fnc<borrow mut Box, void> handler =
                    |borrow mut s| -> void {
                      s.value = s.value + 1;
                      return;
                    };
                  return;
                }
                """);
        rejects("""
                fnc invalid(): void {
                  val Fnc<borrow mut Box, void> handler =
                    |borrow mut s| -> void {
                      s = new Box();
                      return;
                    };
                  return;
                }
                """);
    }
}
