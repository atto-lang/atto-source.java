package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Real compiler-owned `using` parser/typechecker/evaluator guest contract. */
@Timeout(20)
final class TelemetryUsingStdApiGuestTest {
    @Test
    void compilerOwnedSpanAcceptsCanonicalObserversWithoutGuestDefinedProvider() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores")
                .allowAllAccess(false).out(output).build()) {
            context.eval("ores", """
                    pub fnc main(): void {
                      stdio.stdout.write("before|");
                      using telemetry.span("checkout") as span {
                        span.set_attribute("cart.items", 3);
                        span.add_event("payment_started");
                        span.attribute("checkout.stage", "payment");
                        stdio.stdout.write(span.recording());
                      }
                      stdio.stdout.write("|after");
                      return;
                    }
                    """);
        }
        assertEquals("before|false|after",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void observerCallsCannotForgeArgumentsOrEscapeLexicalAlias() {
        for (String invalidBody : new String[] {
                "span.add_event(42);",
                "span.add_event(\"one\", \"two\");",
                "span.set_attribute(\"name\");",
                "span.add_event;"
        }) {
            try (Context context = Context.newBuilder("ores")
                    .allowAllAccess(false).build()) {
                String source = """
                        pub fnc main(): void {
                          using telemetry.span("checkout") as span {
                            %s
                          }
                          return;
                        }
                        """.formatted(invalidBody);
                assertThrows(PolyglotException.class,
                        () -> context.eval("ores", source), invalidBody);
            }
        }
    }

    @Test
    void scopeAliasCannotBeReturnedOutsideItsLexicalOwnership() {
        try (Context context = Context.newBuilder("ores")
                .allowAllAccess(false).build()) {
            assertThrows(PolyglotException.class, () -> context.eval("ores", """
                    pub fnc leak() -> Any {
                      using telemetry.span("checkout") as span {
                        return span;
                      }
                    }
                    pub fnc main(): void {
                      leak();
                      return;
                    }
                    """));
        }
    }
}
