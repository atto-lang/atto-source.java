package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class TernaryAggregateArmTest {
    private static void parse(String expression) {
        Parser.parseStrictAtto("fnc f(bool c): void { val v = " + expression + "; return; }");
    }

    @Test void explicitAggregateAndSingleResultArmsParse() {
        for (String expr : new String[]{
                "c ? struct{status: 42} : struct{status: 7}",
                "c ? Record.of(status: 42) : Record.of(status: 7)",
                "c ? struct{} : struct{}",
                "c ? Record.of() : Record.of()",
                "c ? { return struct{status: 42}; } : struct{status: 7}",
                "c ? Record.of(status: 7) : { ^ Record.of(status: 42); }",
                "c ? Record.of(status: false ? 7 : 42) : Record.of(status: 0)",
                "c ? struct{status: int}{status: 42} : struct{status: int}{status: 7}",
                "c ? { return 42; } : { ^ 7; }"}) {
            assertDoesNotThrow(() -> parse(expr), expr);
        }
    }

    @Test void bareBracesNeverBecomeAggregatesOrImplicitTailBlocks() {
        for (String expr : new String[]{
                "c ? {} : {}",
                "c ? 42 : {}",
                "c ? {status: 42} : Record.of(status: 7)",
                "c ? Record.of(status: 7) : {status: 42}",
                "c ? {42} : 7",
                "c ? { val x = 42; return x; } : 7"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> parse(expr), expr);
            assertTrue(error.getMessage().contains("E_VALUE_ARM"), error::getMessage);
        }
    }

    @Test void legacyExplicitStructSyntaxStillParses() {
        assertDoesNotThrow(() -> Parser.parse("fnc f(): void { val x = struct{status: int}{status: 42}; return; }"));
        assertDoesNotThrow(() -> Parser.parse("fnc f(): void { val x = infer struct{status: 42}; return; }"));
    }

    @Test void armReturnDoesNotReturnFromFunctionAndOnlySelectedArmExecutes() throws Exception {
        String program = """
                fnc yes(): int { stdio.stdout.write("Y"); return 40; }
                fnc no(): int { stdio.stdout.write("N"); return 5; }
                pub routine main(): void {
                    val int x = true ? { return yes(); } : { ^ no(); };
                    val int y = false ? { return yes(); } : { ^ no(); };
                    stdio.stdout.write(x + y + 1);
                }
                """;
        var checked = OresCompiler.parseAndTypeCheckStrictAtto(program);
        var out = new ByteArrayOutputStream();
        String name = "ternary-aggregate-arm.ores";
        try (var lease = OresLanguage.openPrecheckedProgram(name, program, checked);
             Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, name).build());
        }
        assertEquals("YN46", out.toString(StandardCharsets.UTF_8));
    }
}
