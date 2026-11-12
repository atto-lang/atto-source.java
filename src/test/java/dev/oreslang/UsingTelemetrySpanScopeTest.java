package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A lexical using telemetry.span(...) scope is ordinary Atto guest code.
 * Its cleanup is lowered into the existing cancellation-safe try/finally path;
 * no Java agent, host callback or runtime bytecode rewrite is involved.
 *
 * These tests deliberately use a minimal user-defined telemetry object to
 * verify the language construct independently of an unfinished OTLP exporter.
 */
@Timeout(20)
final class UsingTelemetrySpanScopeTest {
    private static final String TYPES = """
            define class TestSpan as
              pub constructor() { }
              pub fnc end(): void {
                stdio.stdout.write("E");
                return;
              }
              pub fnc annotate(): void {
                stdio.stdout.write("A");
                return;
              }
            end
            define class Telemetry as
              pub constructor() { }
              pub fnc span(String label): TestSpan {
                return new TestSpan();
              }
            end
            """;

    private static String program(String statement) {
        return TYPES + "pub fnc main(): void { val telemetry = new Telemetry();\n"
                + statement + "\nreturn; }\n";
    }

    @Test
    void parsesToSingleEvaluationBindingAndCheckedFinallyBlock() {
        Ast.FunctionDecl fn = (Ast.FunctionDecl) Parser.parse(program("""
                using telemetry.span("checkout") as span {
                  span.annotate();
                }
                """)).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.FunctionDecl
                        && ((Ast.FunctionDecl) decl).name().equals("main"))
                .findFirst().orElseThrow();
        Ast.BlockStmt scope = assertInstanceOf(Ast.BlockStmt.class, fn.body().get(1));
        assertEquals(2, scope.body().size());
        Ast.BindingStmt binding = assertInstanceOf(Ast.BindingStmt.class, scope.body().getFirst());
        assertEquals("span", binding.name());
        assertInstanceOf(Ast.CallExpr.class, binding.initializer());
        Ast.TryStmt guarded = assertInstanceOf(Ast.TryStmt.class, scope.body().get(1));
        assertFalse(guarded.hasCatch());
        assertEquals(1, guarded.finallyBody().size());
        Ast.ExprStmt finalCall = assertInstanceOf(Ast.ExprStmt.class, guarded.finallyBody().getFirst());
        Ast.CallExpr end = assertInstanceOf(Ast.CallExpr.class, finalCall.expression());
        Ast.MemberExpr member = assertInstanceOf(Ast.MemberExpr.class, end.callee());
        assertEquals("end", member.member());
    }

    @Test
    void normalScopeInvokesEndAfterBody() throws Exception {
        assertEquals("BAE", run(program("""
                using telemetry.span("checkout") as span {
                  stdio.stdout.write("B");
                  span.annotate();
                }
                """), new ByteArrayOutputStream()));
    }

    @Test
    void scopeInvokesEndOnReturn() throws Exception {
        assertEquals("E9", run(TYPES + """
                pub fnc compute(): int {
                  val telemetry = new Telemetry();
                  using telemetry.span("compute") as span {
                    return 9;
                  }
                }
                pub fnc main(): void {
                  stdio.stdout.write(compute());
                  return;
                }
                """, new ByteArrayOutputStream()));
    }

    @Test
    void scopeInvokesEndOnGuestFailure() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(PolyglotException.class, () -> run(program("""
                using telemetry.span("failure") as span {
                  val Array<int> missing = new Array<int>();
                  missing.get(0);
                }
                """), output));
        assertEquals("E", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void nestedScopesCloseInLifoOrder() throws Exception {
        assertEquals("EE", run(program("""
                using telemetry.span("outer") as outer {
                  using telemetry.span("inner") as inner {
                  }
                }
                """), new ByteArrayOutputStream()));
    }

    @Test
    void awaitInsideScopeUsesExistingSuspendingFinallyPath() throws Exception {
        assertEquals("E3", run(TYPES + """
                pub async fnc tick(): int { rt cooperate; return 3; }
                pub async fnc compute(): int {
                  val telemetry = new Telemetry();
                  using telemetry.span("async") as span {
                    return await tick();
                  }
                }
                pub async fnc main(): void {
                  stdio.stdout.write(await compute());
                  return;
                }
                """, new ByteArrayOutputStream()));
    }

    @Test
    void onlyCanonicalSpanFormIsAcceptedAndBorrowSyntaxRemainsSeparate() {
        for (String body : new String[] {
                "using other.span(\"x\") as span { }",
                "using telemetry.start(\"x\") as span { }",
                "using telemetry.span as span { }",
                "using telemetry.span(\"x\") as _ { }"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(program(body)), body);
        }
        Ast.FunctionDecl fn = (Ast.FunctionDecl) Parser.parse(program("using(1);"))
                .modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.FunctionDecl
                        && ((Ast.FunctionDecl) decl).name().equals("main"))
                .findFirst().orElseThrow();
        assertInstanceOf(Ast.ExprStmt.class, fn.body().get(1));
    }

    private static String run(String code, ByteArrayOutputStream output) throws Exception {
        Source src = Source.newBuilder(OresLanguage.ID, code, "using-telemetry-span.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(src);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
