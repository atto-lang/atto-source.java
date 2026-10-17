package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class LambdaReturnAndTupleSyntaxTest {

    @Test
    void typedLambdaReturnAnnotationSurvivesParsing() {
        Ast.Program parsed = Parser.parse("""
                fnc f(): void {
                  val x = |int n|: int -> n * 3;
                  return;
                }
                """);
        Ast.FunctionDecl function = (Ast.FunctionDecl) parsed.modules().getFirst()
                .declarations().getFirst();
        Ast.BindingStmt binding = (Ast.BindingStmt) function.body().getFirst();
        Ast.LambdaExpr lambda = assertInstanceOf(Ast.LambdaExpr.class, binding.initializer());
        assertNotNull(lambda.returnType());
        assertEquals("int", lambda.returnType().name());
        assertNotNull(lambda.expressionBody());
    }

    @Test
    void typedAndContextualLambdasAcceptCheckedScalarCast() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type Mapper = (int) => int;
                fnc f(): void {
                  val Mapper mapper = |int n| -> (n * 3) as int;
                  val Mapper annotated = |int n|: int -> n * 3;
                  val x = |int n|: int -> n * 3;
                  val zero = ||: int -> 42;
                  val parenthesized = (int n): int -> n * 3;
                  val block = |int n|: int -> { return n * 3; };
                  return;
                }
                """)));
    }

    @Test
    void declaredLambdaResultMustAgreeWithBodyAndContext() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val wrong = |int n|: bool -> n * 3;
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                type Mapper = (int) => int;
                fnc f(): void {
                  val Mapper wrong = |int n|: bool -> n > 0;
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val wrong = |int n|: int -> { val x = n + 1; };
                  return;
                }
                """)));
    }

    @Test
    void onlyExplicitTupleConstructionCreatesTupleValues() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val n = 3;
                  val scalar = (n * 3) as int;
                  val one = tuple(n * 3) as Tuple[int];
                  val lower = tuple(n * 3) as tuple[int];
                  val pair = tuple(n, n * 3);
                  return;
                }
                """)));
        IllegalArgumentException legacy = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc f(): void {
                          val legacy = (1, 2);
                          return;
                        }
                        """));
        assertTrue(legacy.getMessage().contains("tuple(...)"), legacy::toString);
    }

    @Test
    void ternaryBodyIsNotConsumedByReturnAnnotation() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val f = |bool flag|: int -> flag ? 10 : 20;
                  return;
                }
                """)));
    }
}
