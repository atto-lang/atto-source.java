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

final class TupleEmptyAndPatternBoundaryTest {
    @Test
    void emptyCompactAndExplicitTuplesShareAstAndGroupingStaysScalar() {
        Ast.Program program = Parser.parse("""
                fnc main(): void {
                  const compact = ();
                  const explicit = tuple {};
                  const parenthesized = (42);
                  const singleton = (42,);
                  const explicitSingleton = tuple {42};
                  return;
                }
                """);
        Ast.FunctionDecl fn = assertInstanceOf(
                Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        for (int i : new int[]{0, 1}) {
            assertTrue(assertInstanceOf(
                    Ast.TupleExpr.class,
                    assertInstanceOf(Ast.BindingStmt.class, fn.body().get(i)).initializer())
                    .elements().isEmpty());
        }
        assertInstanceOf(
                Ast.LiteralExpr.class,
                assertInstanceOf(Ast.BindingStmt.class, fn.body().get(2)).initializer());
        for (int i : new int[]{3, 4}) {
            assertEquals(1, assertInstanceOf(
                    Ast.TupleExpr.class,
                    assertInstanceOf(Ast.BindingStmt.class, fn.body().get(i)).initializer())
                    .elements().size());
        }
        OwnershipChecker.check(TypeChecker.check(program));
    }

    @Test
    void lambdaLookaheadStillOwnsEmptyParameterList() {
        Ast.Program program = Parser.parse("""
                fnc main(): void {
                  const f = () -> { return 42; };
                  return;
                }
                """);
        Ast.FunctionDecl fn = assertInstanceOf(
                Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertInstanceOf(
                Ast.LambdaExpr.class,
                assertInstanceOf(Ast.BindingStmt.class, fn.body().getFirst()).initializer());
    }

    @Test
    void emptySingletonAndMultiTuplePatternsTypecheckWithExactArity() {
        String source = """
                fnc emptyCompact(): int {
                  match () over
                    on () -> { return 1; }
                  end
                }

                fnc emptyExplicit(): int {
                  match tuple {} over
                    on tuple {} -> { return 2; }
                  end
                }

                fnc singleCompact(): int {
                  match (42,) over
                    on (n,) -> { return n; }
                  end
                }

                fnc singleExplicit(): int {
                  match tuple {43} over
                    on tuple {n,} -> { return n; }
                  end
                }

                fnc many(): int {
                  match tuple {1, "two"} over
                    on tuple {n, label,} -> { return n; }
                  end
                }
                """;
        OwnershipChecker.check(TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void tuplePatternsNeverBecomeArrayPatternsOrGroupingSyntax() {
        IllegalArgumentException array = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): int {
                          match [1, 2] over
                            on tuple {a, b} -> { return a; }
                            on _ -> { return 0; }
                          end
                        }
                        """)));
        assertTrue(array.getMessage().contains("tuple pattern requires"), array.getMessage());

        IllegalArgumentException grouping = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(): int {
                          match (1,) over
                            on (x) -> { return x; }
                          end
                        }
                        """));
        assertTrue(grouping.getMessage().contains("comma"), grouping.getMessage());

        IllegalArgumentException wrongArity = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): int {
                          match tuple {1, 2} over
                            on tuple {one} -> { return one; }
                            on _ -> { return 0; }
                          end
                        }
                        """)));
        assertTrue(wrongArity.getMessage().contains("arity"), wrongArity.getMessage());
    }

    @Test
    void guestExecutesEmptySingletonAndMultiTupleMatchPatterns() throws Exception {
        String source = """
                fnc emptyCompact(): int {
                  match () over
                    on () -> { return 1; }
                  end
                }
                fnc emptyExplicit(): int {
                  match tuple {} over
                    on tuple {} -> { return 2; }
                  end
                }
                fnc singleton(): int {
                  match (42,) over
                    on (n,) -> { return n; }
                  end
                }
                fnc explicitSingleton(): int {
                  match tuple {43} over
                    on tuple {n,} -> { return n; }
                  end
                }
                fnc mixed(): int {
                  match (5, "five") over
                    on tuple {n, name,} -> { return n; }
                  end
                }
                pub fnc main(): void {
                  stdio.println(emptyCompact());
                  stdio.println(emptyExplicit());
                  stdio.println(singleton());
                  stdio.println(explicitSingleton());
                  stdio.println(mixed());
                  return;
                }
                """;
        OwnershipChecker.check(TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "tuple-pattern-boundary.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(guest);
        }
        assertEquals("1\n2\n42\n43\n5",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }

    @Test
    void forOfPreservesArrayVsTupleUnderBothBodySpellings() throws Exception {
        String code = """
                fnc fromArray(): int {
                  let int total = 0;
                  for let value of [1, 2, 3] do
                    total = total + value;
                  done
                  return total;
                }
                fnc fromCompactTuple(): int {
                  let int total = 0;
                  for let value of (1, 2, 3) {
                    total = total + value;
                  }
                  return total;
                }
                fnc fromExplicitTuple(): int {
                  let int total = 0;
                  for let value of tuple {1, 2, 3} do
                    total = total + value;
                  done
                  return total;
                }
                pub fnc main(): void {
                  stdio.println(fromArray());
                  stdio.println(fromCompactTuple());
                  stdio.println(fromExplicitTuple());
                  return;
                }
                """;
        Ast.Program parsed = Parser.parse(code);
        OwnershipChecker.check(TypeChecker.check(parsed));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, code, "tuple-iterators.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(guest);
        }
        assertEquals("6\n6\n6",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }

    @Test
    void zeroElementTupleCannotInventPermissiveForOfElementType() {
        for (String iterable : new String[]{"()", "tuple {}"}) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("""
                            fnc bad(): void {
                              for let value of %s do
                                const int unsafelyNarrowed = value;
                              done
                              return;
                            }
                            """.formatted(iterable))));
            assertTrue(error.getMessage().contains("empty tuple"), error.getMessage());
        }
    }
}
