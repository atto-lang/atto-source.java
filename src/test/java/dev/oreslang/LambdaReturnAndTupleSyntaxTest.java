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
    void structuralRecordsRemainDistinctFromTuples() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val point = struct{x: int, y: int}{x: 3, y: 4};
                  val reduced = point as {x: int};
                  val positional = tuple(3, 4) as Tuple[int, int];
                  return;
                }
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val nested = tuple(struct{x: int}{x: 3}) as Tuple[{x: int}];
                  val deeper = tuple(tuple(struct{x: int}{x: 4})) as Tuple[Tuple[{x: int}]];
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val nested = tuple(infer struct{x: 3}) as Tuple[{x: int}];
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val mistaken = tuple(3) as {x: int};
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val mistaken = struct{x: int}{x: 3} as Tuple[int];
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val readonly = infer struct{x: 3};
                  val upgraded = readonly as {x: int};
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val missingField = struct{x: int}{x: 3} as {y: int};
                  return;
                }
                """)));
    }

    @Test
    void explicitTuplePatternsSupportSingletonAndMultiElementTuples() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc classify(): int {
                  match tuple(7) over
                    on tuple(7) -> { return 1; }
                    on _ -> { return 0; }
                  end
                }
                fnc classifyPair(): int {
                  match tuple(7, 9) over
                    on tuple(7, 9) -> { return 1; }
                    on _ -> { return 0; }
                  end
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc invalid(): int {
                  match tuple(7) over
                    on tuple() -> { return 1; }
                    on _ -> { return 0; }
                  end
                }
                """));
    }

    @Test
    void exactUserFacingColonBindingSyntaxHasScalarResult() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type Mapper = (int) => int;
                fnc f(): void {
                  const mapper: Mapper = |int n| -> (n * 3) as int;
                  const row = tuple(3) as tuple[int];
                  return;
                }
                """)));
    }

    @Test
    void recordFieldNamesIgnoreOrderButTuplesRetainPosition() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val reversed = struct{x: int, y: bool}{y: true, x: 7};
                  val projected = reversed as {y: bool, x: int};
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val wrongOrder = tuple(7, true) as Tuple[bool, int];
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val wrongArity = tuple(7, true) as Tuple[int];
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val duplicated = infer struct{x: 7, x: 8};
                  return;
                }
                """)));
    }

    @Test
    void unrelatedOrErasedTupleCastsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val notATuple = 7 as tuple[int];
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc f(): void {
                  val incompatible = tuple(7) as Tuple[bool];
                  return;
                }
                """)));
    }

    @Test
    void typedClosureDoesNotPrematurelyInitializeCapturedStructFields() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type Setter = (int) => void;
                fnc accepted(): void {
                  let mut row = struct{a: int, b: int}{a: 1};
                  const Setter deferred = |int n|: void -> { row.b = n; return; };
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                type Setter = (int) => void;
                fnc invalid(): void {
                  let mut row = struct{a: int, b: int}{a: 1};
                  const Setter deferred = |int n|: void -> { row.b = n; return; };
                  stdio.println(row.b);
                  return;
                }
                """)));
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
