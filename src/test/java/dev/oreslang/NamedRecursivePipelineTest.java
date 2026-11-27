package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the actual Java language guest, not a Java Fibonacci substitute. */
final class NamedRecursivePipelineTest {
    private static String fib(String upstream) {
        return upstream + """
                 |> fnc fib(int v): int {
                    ^ match v over
                        on 0 -> 0,
                        on 1 -> 1,
                        on _ -> fib(v - 2) + fib(v - 1)
                    end
                }
                """;
    }
    private static void execute(String program, String expected) throws Exception {
        OwnershipChecker.check(TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).option("engine.WarnInterpreterOnly", "false")
                .out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "named-stage.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals(expected, output.toString(StandardCharsets.UTF_8));
    }
    @Test void exactSourceChecksWithoutExpensiveExecution() {
        OwnershipChecker.check(TypeChecker.check(Parser.parse(
                "pub fnc main(): int { return " + fib("55") + "; }")));
    }
    @Test @Timeout(15) void actualSmallGuests() throws Exception {
        execute("pub fnc main(): void { stdio.stdout.write(" + fib("0")
                + "); stdio.stdout.write(\":\"); stdio.stdout.write(" + fib("1")
                + "); stdio.stdout.write(\":\"); stdio.stdout.write(" + fib("10")
                + "); return; }", "0:1:55");
    }
    @Test @Timeout(15) void lexicalShadowAndOnceOnlyUpstream() throws Exception {
        execute("""
                fnc fib(int v): int { return 999; }
                fnc upstream(): int { stdio.stdout.write("U"); return 10; }
                pub fnc main(): void {
                    stdio.stdout.write(
                """ + fib("upstream()") + """
                    );
                    stdio.stdout.write(":");
                    stdio.stdout.write(fib(10));
                    return;
                }
                """, "U55:999");
    }
    @Test @Timeout(15) void stageReturnDoesNotExitCallerAndPreservesI64() throws Exception {
        execute("""
                pub fnc main(): void {
                    stdio.stdout.write(1 |> fnc f(int v): int { return v+41; });
                    stdio.stdout.write(":");
                    stdio.stdout.write(0 |> fnc f(int v): int {
                        return match v over on 0 -> 9223372036854775807, on _ -> f(v-1) end;
                    });
                    return;
                }
                """, "42:9223372036854775807");
    }
    @Test void noUnsupportedCaptureOrEscape() {
        for (String source : new String[] {
                "pub fnc main(): int { const int x = 2; return 1 |> fnc f(int v): int { return v+x; }; }",
                "pub fnc main(): int { return 1 |> fnc f(int v): int { return f(v,v); }; }",
                "pub fnc main(): int { return 1 |> fnc f(int f): int { return f; }; }",
                "pub fnc main(): int { return 1 |> fnc f(int v) { return v; }; }",
                "pub fnc main(): int { return 1 |> fnc f(int v): int { return v; return 0; }; }",
                "pub fnc main(): int { const int x = 1 |> fnc f(int v): int { return v; }; return f(x); }"
        }) {
            assertThrows(RuntimeException.class,
                    () -> OwnershipChecker.check(TypeChecker.check(Parser.parse(source))), source);
        }
    }
}
