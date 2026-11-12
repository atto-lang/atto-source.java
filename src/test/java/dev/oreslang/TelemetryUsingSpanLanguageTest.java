package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.TelemetrySpanScope;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Real parsed, checked guest code with a lexical span surviving await turns. */
@Timeout(30)
final class TelemetryUsingSpanLanguageTest {
    @Test
    void canonicalSpanIsDistinctFromBorrowAndSupportsOptionalName() {
        String source = """
                pub async fnc main(): void {
                  using telemetry.span("outer") as span {
                    using telemetry.span() as inner {
                      inner.attribute("operation", "checkout");
                    }
                  }
                  return;
                }
                """;
        Ast.FunctionDecl function = (Ast.FunctionDecl) Parser.parse(source).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(declaration -> declaration instanceof Ast.FunctionDecl)
                .findFirst().orElseThrow();
        Ast.UsingTelemetrySpanStmt outer = assertInstanceOf(
                Ast.UsingTelemetrySpanStmt.class, function.body().getFirst());
        assertEquals("span", outer.binding());
        Ast.UsingTelemetrySpanStmt inner = assertInstanceOf(
                Ast.UsingTelemetrySpanStmt.class, outer.body().getFirst());
        assertEquals("inner", inner.binding());
        assertInstanceOf(Ast.LiteralExpr.class, inner.name());
        TypeChecker.check(Parser.parse(source));
    }

    @Test
    void nestedSpansSurviveRealAwaitAndRestoreOuterSpan() throws Exception {
        TelemetrySpanScope.enableExistingSites(100, 10_000, "");
        try {
            long started = TelemetrySpanScope.startedCount();
            long ended = TelemetrySpanScope.endedCount();
            String stdout = run("""
                    async fnc tick(): int {
                      rt cooperate;
                      return 1;
                    }
                    pub async fnc main(): void {
                      using telemetry.span("outer") as span {
                        stdio.println(span.recording());
                        const trace = span.trace_id();
                        const parent = span.span_id();
                        val int step = await tick();
                        stdio.println(span.trace_id() eq trace);
                        using telemetry.span("inner") as child {
                          stdio.println(child.trace_id() eq trace);
                          stdio.println(child.span_id() eq parent);
                          child.attribute("step", step);
                        }
                        stdio.println(span.span_id() eq parent);
                      }
                      return;
                    }
                    """);
            assertEquals("true\ntrue\ntrue\nfalse\ntrue", stdout);
            assertEquals(started + 2, TelemetrySpanScope.startedCount(),
                    "exactly two guest lexical spans must be created");
            assertEquals(ended + 2, TelemetrySpanScope.endedCount(),
                    "both spans must end, even though the parent was suspended");
        } finally {
            TelemetrySpanScope.disableExistingSites();
        }
    }

    @Test
    void noOpTraceRemainsExecutableWithoutOutputSink() throws Exception {
        TelemetrySpanScope.disableExistingSites();
        String stdout = run("""
                pub async fnc main(): void {
                  using telemetry.span() as span {
                    stdio.println(span.recording());
                    stdio.println(span.trace_id() eq "");
                    span.attribute("safe", 42);
                  }
                  return;
                }
                """);
        assertEquals("false\ntrue", stdout);
    }

    @Test
    void usingScopeRejectsNonStringNamesAndEscapingHandle() {
        PolyglotException wrongName = assertThrows(PolyglotException.class, () -> run("""
                pub async fnc main(): void {
                  using telemetry.span(41) as span { }
                  return;
                }
                """));
        assertTrue(wrongName.getMessage().contains("telemetry.span name"),
                wrongName::getMessage);

        assertThrows(RuntimeException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc leak(): int {
                  using telemetry.span("scope") as span {
                    return span;
                  }
                }
                """)));

        assertThrows(RuntimeException.class, () -> Parser.parse("""
                pub fnc main(): void {
                  using telemetry.span("a", "b") as span { }
                  return;
                }
                """));
    }

    @Test
    void independentTasksCannotCaptureLexicalSpanHandles() {
        assertThrows(RuntimeException.class, () -> {
            Ast.Program checked = TypeChecker.check(Parser.parse("""
                    pub async fnc main(): void {
                      using telemetry.span("outer") as span {
                        let Future<String> child = task {
                          return span.trace_id();
                        };
                        val String result = await child;
                      }
                      return;
                    }
                    """));
            OwnershipChecker.check(checked);
        });
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, "using-telemetry-span.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8)
                .trim()
                .replace("\r", "");
    }
}
