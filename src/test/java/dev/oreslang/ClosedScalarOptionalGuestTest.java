package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.CheckedCallPlan;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executable target profile: direct free functions with int / Option<int>
 * formals, deterministic literal int defaults and typed None omissions.
 * This suite MUST run under the actual GraalVM/JVM guest, not a JS model.
 */
final class ClosedScalarOptionalGuestTest {
    private static final String FUNCTION = """
            fnc calculate(x: int, y?: int = 10, z?: int?): int {
              return x + y;
            }
            """;

    private static String execute(String source) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "closed_scalar.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(guest);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void realGuestExecutesBothFormalOrdersAndMaterializesIntAndNoneDefaults() throws Exception {
        assertEquals("42:30:32:42", execute(FUNCTION + """
                pub fnc main(): void {
                  stdio.stdout.write(calculate(x: 20, y: 22));
                  stdio.stdout.write(":");
                  stdio.stdout.write(calculate(x: 20));
                  stdio.stdout.write(":");
                  stdio.stdout.write(calculate(20, y: 12));
                  stdio.stdout.write(":");
                  stdio.stdout.write(calculate(y: 22, x: 20));
                  return;
                }
                """));
        assertEquals("42:30", execute("""
                fnc calculate(int x, int y? = 10, int? z?): int {
                  return x + y;
                }
                pub fnc main(): void {
                  stdio.stdout.write(calculate(y: 22, x: 20));
                  stdio.stdout.write(":");
                  stdio.stdout.write(calculate(x: 20));
                  return;
                }
                """));
    }

    @Test
    void sourceOrderEffectsRemainLexicalWhenAnOptionSlotIsOmitted() throws Exception {
        assertEquals("2220:42", execute(FUNCTION + """
                fnc mark(value: int): int {
                  stdio.stdout.write(value);
                  return value;
                }
                pub fnc main(): void {
                  val int result = calculate(y: mark(22), x: mark(20));
                  stdio.stdout.write(":");
                  stdio.stdout.write(result);
                  return;
                }
                """));
    }

    @Test
    void checkedCallPlanHasExactNontrailingOmissionProvenance() {
        Ast.Program checked = TypeChecker.check(Parser.parse(FUNCTION + """
                pub fnc main(): int {
                  return calculate(y: 22, x: 20);
                }
                """));
        Ast.FunctionDecl main = assertInstanceOf(Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().get(1));
        Ast.ReturnStmt result = assertInstanceOf(Ast.ReturnStmt.class,
                main.body().getFirst());
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, result.value());
        CheckedCallPlan plan = CheckedCallPlan.require(call);
        assertEquals(List.of(1, 0, -1), plan.abiToSource());
        assertTrue(plan.isOmitted(2));
        assertEquals(List.of("y", "x"), call.argumentLabels());
    }

    @Test
    void nontrailingDefaultRemainsInItsDeclarationSlot() {
        Ast.Program checked = TypeChecker.check(Parser.parse("""
                fnc choose(a?: int = 10, b: int): int { return a + b; }
                pub fnc main(): int { return choose(b: 32); }
                """));
        Ast.FunctionDecl main = assertInstanceOf(Ast.FunctionDecl.class,
                checked.modules().getFirst().declarations().get(1));
        Ast.CallExpr call = (Ast.CallExpr) ((Ast.ReturnStmt)
                main.body().getFirst()).value();
        assertEquals(List.of(-1, 0), CheckedCallPlan.require(call).abiToSource());
    }

    @Test
    void invalidDefaultsAndOverlappingOverloadsAreStaticallyRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc invalid(x?: int = side_effect()): int { return x; }
                        pub fnc main(): int { return invalid(); }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc invalid(x?: int = 10): int { return x; }
                        fnc invalid(x: int, y?: int = 2): int { return x + y; }
                        pub fnc main(): int { return invalid(x: 20); }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc invalid(x: int?): int { return 42; }
                        pub fnc main(): int { return invalid(); }
                        """)));
    }
}
