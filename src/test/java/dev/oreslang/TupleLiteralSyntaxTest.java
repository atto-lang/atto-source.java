package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TupleLiteralSyntaxTest {
    @Test
    void explicitBraceAndCompactTupleSyntaxShareTheTupleAst() {
        Ast.Program program = Parser.parse("""
                fnc main(): void {
                  const explicit = tuple {1, 2, 3};
                  const compact = (1, 2, 3);
                  return;
                }
                """);

        Ast.FunctionDecl main = assertInstanceOf(
                Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        Ast.BindingStmt explicit = assertInstanceOf(Ast.BindingStmt.class, main.body().get(0));
        Ast.BindingStmt compact = assertInstanceOf(Ast.BindingStmt.class, main.body().get(1));

        Ast.TupleExpr explicitTuple = assertInstanceOf(Ast.TupleExpr.class, explicit.initializer());
        Ast.TupleExpr compactTuple = assertInstanceOf(Ast.TupleExpr.class, compact.initializer());
        assertEquals(3, explicitTuple.elements().size());
        assertEquals(3, compactTuple.elements().size());
    }

    @Test
    void tupleParenthesesRemainAnOrdinaryFunctionCall() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc tuple(int a, int b, int c): int {
                  return a + b + c;
                }

                fnc main(): int {
                  return tuple(1, 2, 3);
                }
                """));

        Ast.FunctionDecl main = program.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("main"))
                .findFirst()
                .orElseThrow();
        Ast.ReturnStmt returned = assertInstanceOf(Ast.ReturnStmt.class, main.body().getFirst());
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, returned.value());
        Ast.NameExpr callee = assertInstanceOf(Ast.NameExpr.class, call.callee());
        assertEquals("tuple", callee.name());
        assertEquals(3, call.arguments().size());
    }

    @Test
    void explicitTupleSupportsZeroOneAndManyElements() {
        Ast.Program program = Parser.parse("""
                fnc main(): void {
                  const empty = tuple {};
                  const singleton = tuple {1};
                  const many = tuple {1, 2, 3};
                  return;
                }
                """);

        Ast.FunctionDecl main = assertInstanceOf(
                Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals(0, assertInstanceOf(
                Ast.TupleExpr.class,
                assertInstanceOf(Ast.BindingStmt.class, main.body().get(0)).initializer())
                .elements().size());
        assertEquals(1, assertInstanceOf(
                Ast.TupleExpr.class,
                assertInstanceOf(Ast.BindingStmt.class, main.body().get(1)).initializer())
                .elements().size());
        assertEquals(3, assertInstanceOf(
                Ast.TupleExpr.class,
                assertInstanceOf(Ast.BindingStmt.class, main.body().get(2)).initializer())
                .elements().size());
    }

    @Test
    void tupleTrailingCommaSupportsSingletonAndManyElementForms() {
        Ast.Program program = Parser.parse("""
                fnc main(): void {
                  const explicitSingleton = tuple {1,};
                  const explicitMany = tuple {1, 2, 3,};
                  const compactSingleton = (1,);
                  const compactMany = (1, 2, 3,);
                  return;
                }
                """);

        Ast.FunctionDecl main = assertInstanceOf(
                Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        for (int i = 0; i < 4; i++) {
            assertInstanceOf(
                    Ast.TupleExpr.class,
                    assertInstanceOf(Ast.BindingStmt.class, main.body().get(i)).initializer());
        }
        assertEquals(1, assertInstanceOf(
                Ast.TupleExpr.class,
                assertInstanceOf(Ast.BindingStmt.class, main.body().get(0)).initializer())
                .elements().size());
        assertEquals(3, assertInstanceOf(
                Ast.TupleExpr.class,
                assertInstanceOf(Ast.BindingStmt.class, main.body().get(1)).initializer())
                .elements().size());
        assertEquals(1, assertInstanceOf(
                Ast.TupleExpr.class,
                assertInstanceOf(Ast.BindingStmt.class, main.body().get(2)).initializer())
                .elements().size());
        assertEquals(3, assertInstanceOf(
                Ast.TupleExpr.class,
                assertInstanceOf(Ast.BindingStmt.class, main.body().get(3)).initializer())
                .elements().size());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void forOfAcceptsExplicitBraceTuple() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc take(int value): void {
                  return;
                }

                fnc main(): void {
                  for let value of tuple {1, 2, 3} {
                    take(value);
                  }
                  return;
                }
                """)));
    }

    @Test
    void namedInferenceUsesBraceTupleAndKeepsLegacyParenthesizedCompatibility() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc canonical(): int {
                  const T values = infer<T> tuple {1, 2, 3};
                  return values[1];
                }

                fnc compatibility(): int {
                  const U values = infer<U> tuple (4, 5, 6);
                  return values[1];
                }
                """)));
    }
    @Test
    void namedTupleInferenceAdmitsTrailingCommaButStillRejectsEmpty() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc canonical(): int {
                  const T values = infer<T> tuple {1, 2, 3,};
                  return values[2];
                }

                fnc compatibility(): int {
                  const U values = infer<U> tuple (4, 5, 6,);
                  return values[2];
                }
                """)));

        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(): void {
                          const T values = infer<T> tuple {};
                          return;
                        }
                        """));
        assertTrue(empty.getMessage().contains("at least one"), empty.getMessage());
    }

    @Test
    void legacyTupleCallIsParsedAsOrdinaryCallAndFallsBackToBuiltinCallable() {
        Ast.Program parsed = Parser.parse("""
                fnc main(): int {
                  const values = tuple(1, 2, 3);
                  return values[1];
                }
                """);

        Ast.FunctionDecl main = assertInstanceOf(
                Ast.FunctionDecl.class,
                parsed.modules().getFirst().declarations().getFirst());
        Ast.BindingStmt binding = assertInstanceOf(
                Ast.BindingStmt.class, main.body().getFirst());
        Ast.CallExpr call = assertInstanceOf(
                Ast.CallExpr.class, binding.initializer());
        assertEquals("tuple",
                assertInstanceOf(Ast.NameExpr.class, call.callee()).name());
        assertDoesNotThrow(() -> TypeChecker.check(parsed));
    }


    @Test
    void anyUserTupleFunctionFamilyDisablesBuiltinFallbackForOtherArities() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc tuple(int value): int {
                          return value;
                        }

                        fnc main(): int {
                          const values = tuple(1, 2, 3);
                          return values[0];
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("tuple")
                        || failure.getMessage().contains("call"),
                failure.getMessage());
    }

    @Test
    void explicitBraceTupleNeverResolvesThroughUserTupleFunction() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc tuple(int a, int b, int c): int {
                  return a + b + c;
                }

                fnc main(): int {
                  const values = tuple {1, 2, 3};
                  return values[1];
                }
                """)));
    }

    @Test
    void heterogeneousTupleIterationUsesUnionInsteadOfUnknown() {
        assertDoesNotThrow(() -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    fnc good(): void {
                      for let value of (1, "two") {
                        const int | string joined = value;
                      }
                      return;
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        IllegalArgumentException unsafeNarrowing = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          for let value of (1, "two") {
                            const int narrowed = value;
                          }
                          return;
                        }
                        """)));
        assertTrue(
                unsafeNarrowing.getMessage().contains("expected int")
                        || unsafeNarrowing.getMessage().contains("type"),
                unsafeNarrowing.getMessage());
    }


    @Test
    void tupleLiteralFormsExecuteAlongsideOrdinaryUserTupleFunction() throws Exception {
        String code = """
                fnc tuple(int a, int b): int {
                  return a + b;
                }

                pub fnc main(): void {
                  const explicit = tuple {10, 11,};
                  const compact = (10, 11,);
                  const explicitSingleton = tuple {42,};
                  const compactSingleton = (42,);
                  stdio.println(explicit[0] + compact[1]);
                  stdio.println(explicitSingleton[0] + compactSingleton[0]);
                  stdio.println(tuple(20, 22));
                  return;
                }
                """;

        Ast.Program typed = TypeChecker.check(Parser.parse(code));
        OwnershipChecker.check(typed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, code, "tuple-literal-runtime.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals(
                "21\n84\n42",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }

}
