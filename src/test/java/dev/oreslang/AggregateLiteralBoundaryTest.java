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

/** Regression gates for the positional tuple / structural record / concrete struct boundary. */
final class AggregateLiteralBoundaryTest {
    @Test
    void explicitBraceTupleIsPositionalNotAnArrayOrObject() {
        String source = """
                fnc values(): int {
                  const pair = tuple {1, 2};
                  return pair[0];
                }
                """;
        Ast.Program program = Parser.parse(source);
        Ast.FunctionDecl function = (Ast.FunctionDecl) program.modules().getFirst()
                .declarations().stream().filter(Ast.FunctionDecl.class::isInstance)
                .findFirst().orElseThrow();
        Ast.BindingStmt binding = (Ast.BindingStmt) function.body().getFirst();
        assertInstanceOf(Ast.TupleExpr.class, binding.initializer());
        assertEquals(2, ((Ast.TupleExpr) binding.initializer()).elements().size());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void tupleFormsSupportHeterogeneousSingletonAndTrailingCommaElements() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc choose(): string {
                  const mixed = tuple {1, "two", true,};
                  const explicitSingle = tuple {"ok"};
                  const compactSingle = ("compact",);
                  const compactPair = (1, 2,);
                  return explicitSingle[0];
                }
                """)));
    }

    @Test
    void tupleBraceSyntaxExecutesThroughTheExistingTupleRuntime() throws Exception {
        String program = """
                pub routine main(): void {
                  const pair = tuple {20, 22};
                  stdio.stdout.write(pair[0] + pair[1]);
                  return;
                }
                """;
        assertEquals("42", run(program));
    }

    @Test
    void tupleRecordAndStructKeepDifferentIndexDomains() {
        IllegalArgumentException tupleKey = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): int {
                          const pair = tuple {1, 2};
                          return pair["0"];
                        }
                        """)));
        assertTrue(tupleKey.getMessage().contains("index"), tupleKey.getMessage());

        IllegalArgumentException recordKey = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): int {
                          const row = record{x: 1};
                          return row[0];
                        }
                        """)));
        assertTrue(recordKey.getMessage().contains("field key"), recordKey.getMessage());

        IllegalArgumentException structKey = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): int {
                          const Point point = infer<Point> struct{x: 1};
                          return point[0];
                        }
                        """)));
        assertTrue(structKey.getMessage().contains("field key"), structKey.getMessage());
    }

    @Test
    void mutationAuthorityDoesNotCollapseAcrossAggregateKinds() {
        IllegalArgumentException tupleMutation = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          val pair = tuple {1, 2};
                          pair[0] = 3;
                          return;
                        }
                        """)));
        assertTrue(tupleMutation.getMessage().contains("immutable products"), tupleMutation.getMessage());

        IllegalArgumentException recordMutation = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          val row = record{x: 1};
                          row["x"] = 2;
                          return;
                        }
                        """)));
        assertTrue(recordMutation.getMessage().contains("readonly structural record"), recordMutation.getMessage());

        String mutableStruct = """
                fnc ok(): int {
                  val Point point = infer<Point> struct{x: 1, y: 2};
                  point["x"] = 7;
                  return point.x;
                }
                """;
        var typed = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(mutableStruct)));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));

        String constStruct = """
                fnc bad(): int {
                  const Point point = infer<Point> struct{x: 1, y: 2};
                  point["x"] = 7;
                  return point.x;
                }
                """;
        var constTyped = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(constStruct)));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(constTyped));
    }

    @Test
    void tupleValueDomainDoesNotSilentlyEraseIntoRecordOrStruct() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  const pair = tuple {1, 2};
                  const row = record{x: 1, y: 2};
                  return pair == row;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  const pair = tuple {1, 2};
                  const Point point = infer<Point> struct{x: 1, y: 2};
                  return pair == point;
                }
                """)));
    }

    @Test
    void closedAggregateKindsRemainDistinctInAst() {
        Ast.Program program = Parser.parse("""
                fnc sample(): void {
                  const t = tuple {1, 2};
                  const r = record{x: 1, y: 2};
                  const s = infer struct{x: 1, y: 2};
                  return;
                }
                """);
        Ast.FunctionDecl function = (Ast.FunctionDecl) program.modules().getFirst()
                .declarations().stream().filter(Ast.FunctionDecl.class::isInstance)
                .findFirst().orElseThrow();
        assertInstanceOf(Ast.TupleExpr.class,
                ((Ast.BindingStmt) function.body().get(0)).initializer());
        Ast.ObjectExpr record = (Ast.ObjectExpr)
                ((Ast.BindingStmt) function.body().get(1)).initializer();
        Ast.ObjectExpr struct = (Ast.ObjectExpr)
                ((Ast.BindingStmt) function.body().get(2)).initializer();
        assertEquals(Ast.ObjectKind.RECORD, record.kind());
        assertEquals(Ast.ObjectKind.INFERRED_STRUCT, struct.kind());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "aggregate-literal-boundary.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
