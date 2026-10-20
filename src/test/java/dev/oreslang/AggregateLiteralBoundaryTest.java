package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Regression gates for the positional tuple / record / concrete struct boundary. */
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
    void braceTupleSupportsHeterogeneousAndSingletonElements() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc choose(): string {
                  const mixed = tuple {1, "two", true};
                  const single = tuple {"ok"};
                  return single[0];
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
}
