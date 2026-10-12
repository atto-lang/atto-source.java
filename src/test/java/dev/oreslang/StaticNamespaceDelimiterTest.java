package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

final class StaticNamespaceDelimiterTest {
    @Test void dotAndColonExecuteAndColonBypassesLocalShadow() {
        String source = """
            define class MathOps as
                pub static fnc twice(int x): int { return x * 2; }
            end
            pub routine main(): void {
                stdio.stdout.write(MathOps.twice(3));
                const MathOps = 99;
                stdio.stdout.write(MathOps::twice(4));
                return;
            }
            """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder(OresLanguage.ID).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "static-colon.ores").buildLiteral());
        }
        assertEquals("68", output.toString());
    }

    @Test void colonPreservesPrivateVisibilityAndRejectsValueReceivers() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            define class C as static fnc hidden(): int { return 1; } end
            pub routine main(): void { const n = C::hidden(); return; }
            """)));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
            pub routine main(): void { const n = (1 + 2)::method(); return; }
            """));
    }

    @Test void delimiterDoesNotMakeAnInstanceMethodStatic() {
        var failure = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            define class C as pub method(): int { return 1; } end
            pub routine main(): void { const n = C::method(); return; }
            """)));
        assertTrue(failure.getMessage().contains("static"), failure::getMessage);
    }
}
