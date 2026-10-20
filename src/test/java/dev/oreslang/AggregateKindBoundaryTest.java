package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Salvaged semantic gates from superseded aggregate PR #1669.
 *
 * <p>These assertions deliberately cover aggregate-kind identity only. Tuple syntax,
 * computed access, mutation authority and nominal Entry behavior live in the focused
 * #1668/#1688 suites.
 */
final class AggregateKindBoundaryTest {
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

        Ast.FunctionDecl function = assertInstanceOf(
                Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());

        assertInstanceOf(
                Ast.TupleExpr.class,
                assertInstanceOf(Ast.BindingStmt.class, function.body().get(0)).initializer());

        Ast.ObjectExpr record = assertInstanceOf(
                Ast.ObjectExpr.class,
                assertInstanceOf(Ast.BindingStmt.class, function.body().get(1)).initializer());
        Ast.ObjectExpr struct = assertInstanceOf(
                Ast.ObjectExpr.class,
                assertInstanceOf(Ast.BindingStmt.class, function.body().get(2)).initializer());

        assertEquals(Ast.ObjectKind.RECORD, record.kind());
        assertEquals(Ast.ObjectKind.INFERRED_STRUCT, struct.kind());
        TypeChecker.check(program);
    }

    @Test
    void tupleEqualityCannotStructuralizeIntoRecordOrStruct() {
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
}