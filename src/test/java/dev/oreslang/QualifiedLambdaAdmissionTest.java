package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Staged qualified-callback admission. Parser support MUST NOT silently turn
 * async/suspends callbacks into synchronous closures before lowering is ready.
 */
final class QualifiedLambdaAdmissionTest {
    @Test
    void parsesAsyncSuspendsAndNonLexicalQualifiedLambdas() {
        Ast.Program program = Parser.parse("""
                pub fnc main(): void {
                    val first = async |int headers| -> { stdio.println(headers); };
                    val second = suspends |int headers| -> { stdio.println(headers); };
                    val third = nlex async (int headers) -> { stdio.println(headers); };
                    return;
                }
                """);
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        Ast.LambdaExpr first = assertInstanceOf(Ast.LambdaExpr.class,
                ((Ast.BindingStmt) fn.body().get(0)).initializer());
        Ast.LambdaExpr second = assertInstanceOf(Ast.LambdaExpr.class,
                ((Ast.BindingStmt) fn.body().get(1)).initializer());
        Ast.LambdaExpr third = assertInstanceOf(Ast.LambdaExpr.class,
                ((Ast.BindingStmt) fn.body().get(2)).initializer());
        assertTrue(first.async());
        assertFalse(first.suspends());
        assertFalse(first.nonLexical());
        assertTrue(second.suspends());
        assertFalse(second.async());
        assertTrue(third.async());
        assertTrue(third.nonLexical());
    }

    @Test
    void rejectsContradictoryOrRepeatedLambdaQualifiers() {
        for (String qualifier : new String[] {
                "async suspends", "suspends async", "async async", "nlex nlex"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("pub fnc main(): void { val cb = "
                            + qualifier + " |int x| -> x; return; }"));
            assertTrue(error.getMessage().contains("lambda modifier")
                            || error.getMessage().contains("both async and suspends"),
                    error::getMessage);
        }
    }

    @Test
    void qualifiedLambdasFailClosedUntilExecutableSchedulerLoweringExists() {
        for (String qualifier : new String[] { "async", "suspends" }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck("""
                            pub fnc main(): void {
                                val callback = %s |int x| -> { stdio.println(x); };
                                return;
                            }
                            """.formatted(qualifier)));
            assertTrue(error.getMessage().contains("not yet supported"),
                    error::getMessage);
        }
    }

    @Test
    void laterLocalCannotBeCapturedEvenWhenCallbackWasMarkedAsync() {
        for (String qualifier : new String[] { "", "async", "suspends" }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck("""
                            pub fnc main(): void {
                                val callback = %s |int x| -> {
                                    stdio.println(bar);
                                };
                                let bar = "";
                                return;
                            }
                            """.formatted(qualifier)));
            assertTrue(error.getMessage().contains("unknown name 'bar'"),
                    error::getMessage);
        }
    }

    @Test
    void ordinarySynchronousClosureCanCaptureEarlierInitializedLocal() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): void {
                    let bar = "";
                    val callback = |int x| -> { stdio.println(bar); };
                    return;
                }
                """));
    }
}
