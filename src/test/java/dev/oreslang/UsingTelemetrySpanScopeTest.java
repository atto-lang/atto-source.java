package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java/Graal guest-execution gates for the initial telemetry.span("checkout") scoped
 * resource slice. Transport borrow scopes remain a separate fail-closed AST.
 * This does not implement an OTel exporter, general scoped resource
 * interface, JIT re-instrumentation or independent LLVM AOT parity.
 */
final class UsingTelemetrySpanScopeTest {
    private static final String HEADER = """
            define module telemetry as
              pub define class SpanScope as
                pub close_scope(): void {
                  stdio.stdout.write("C");
                  return;
                }
              end
              pub fnc span(string operation): SpanScope {
                stdio.stdout.write("F");
                return new SpanScope();
              }
            end
            """;

    private static String program(String body) {
        return HEADER + "pub fnc main(): void {\n" + body + "\n}\n";
    }

    private static String run(String source) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source atto = Source.newBuilder(OresLanguage.ID, source, "using-telemetry-scope.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(stdout)
                .build()) {
            context.eval(atto);
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private static Ast.FunctionDecl main(String source) {
        return (Ast.FunctionDecl) Parser.parse(source).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.FunctionDecl fn
                        && fn.name().equals("main"))
                .findFirst().orElseThrow();
    }

    @Test
    void parsesAsTypedLexicalBindingAndFinallyWithExactlyOneCleanupSite() {
        Ast.FunctionDecl fn = main(program("""
                using telemetry.span("checkout") as span {
                  stdio.stdout.write("B");
                }
                return;
                """));
        Ast.BlockStmt using = assertInstanceOf(Ast.BlockStmt.class, fn.body().getFirst());
        assertEquals(2, using.body().size());
        Ast.BindingStmt binding = assertInstanceOf(Ast.BindingStmt.class, using.body().getFirst());
        assertEquals("span", binding.name());
        assertEquals(Ast.BindingKind.VAL, binding.kind());
        Ast.TryStmt tried = assertInstanceOf(Ast.TryStmt.class, using.body().get(1));
        assertEquals(1, tried.finallyBody().size());
        assertInstanceOf(Ast.ExprStmt.class, tried.finallyBody().getFirst());
        assertFalse(tried.hasCatch());
    }

    @Test
    void guestNormalScopeExitClosesAfterBody() throws Exception {
        String code = program("""
                using telemetry.span("checkout") as span {
                  stdio.stdout.write("B");
                }
                stdio.stdout.write("A");
                return;
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(code)));
        assertEquals("FBCA", run(code));
    }

    @Test
    void guestReturnExitClosesBeforeLeavingCallable() throws Exception {
        String code = program("""
                using telemetry.span("checkout") as span {
                  stdio.stdout.write("B");
                  return;
                }
                stdio.stdout.write("never");
                """);
        assertEquals(0, OresCompiler.parseAndTypeCheckWithDiagnostics(code).warnings().size());
        assertEquals("FBC", run(code));
    }

    @Test
    void guestNestedScopesCloseInLifoOrder() throws Exception {
        String code = program("""
                using telemetry.span("checkout") as outer {
                  stdio.stdout.write("A");
                  using telemetry.span("checkout") as inner {
                    stdio.stdout.write("B");
                  }
                  stdio.stdout.write("D");
                }
                return;
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(code)));
        assertEquals("FAFBCDC", run(code));
    }

    @Test
    void typeCheckerRejectsSpanFactoryMissingCloseScopeMethod() {
        String code = """
                define module telemetry as
                  pub define class BrokenSpan as
                  end
                  pub fnc span(string operation): BrokenSpan {
                    return new BrokenSpan();
                  }
                end
                pub fnc main(): void {
                  using telemetry.span("checkout") as span {
                    return;
                  }
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(code)));
    }

    @Test
    void requestBorrowStillFailsClosedUntilLoanProofIsAvailable() {
        String code = """
                pub fnc main(): void {
                  using request.borrow() as view {
                    view.method();
                  }
                  return;
                }
                """;
        Ast.FunctionDecl fn = main(code);
        assertInstanceOf(Ast.UsingBorrowStmt.class, fn.body().getFirst());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(code)));
        assertTrue(denied.getMessage().contains("verified transport-loan"),
                denied::getMessage);
    }

    @Test
    void parserRejectsInvalidScopedBinderWithoutStealingUsingIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(program(
                "using telemetry.span(\"checkout\") as _ { return; }")));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(program(
                "using telemetry.span(\"checkout\") as span;")));
        Ast.FunctionDecl fn = main("pub fnc main(): void { using(1); return; }");
        assertInstanceOf(Ast.ExprStmt.class, fn.body().getFirst());
    }
}
