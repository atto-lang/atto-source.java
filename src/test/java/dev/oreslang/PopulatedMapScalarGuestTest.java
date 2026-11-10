package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase-1 populated Map admission: only immutable scalar literal pairs.
 * No dynamic-key expressions, borrow captures, spreads or map mutations.
 */
final class PopulatedMapScalarGuestTest {
    private static void checked(String program) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
    }

    private static void rejected(String program) {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
    }

    @Test
    void explicitlyTypedAndContextTypedMapGuestsExecute() {
        String program = """
                pub fnc main(): void {
                    const scores = ~map<string, int>("alice" => 100, "bob" => 95);
                    const Map<string, bool> flags = ~map("ready" => true);
                    const Map<Symbol, bool> phases = ~map(#ready => true);
                    const Map<string, int> short_form = ~("hello" => 42);
                    stdio.println(scores.size);
                    stdio.println(flags.size);
                    stdio.println(phases.size);
                    stdio.println(short_form.size);
                    return;
                }
                """;
        checked(program);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = assertDoesNotThrow(() -> Source.newBuilder(
                OresLanguage.ID, program, "populated-map-scalar.atto")
                .mimeType(OresLanguage.MIME_TYPE).build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                context.eval(source);
            }
        });
        assertEquals("2\n1\n1\n1\n",
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void literalMapAdmissionRejectsUnsafeEntries() {
        rejected("fnc bad(): void { const m = ~map(\"x\" => 1); return; }");
        rejected("fnc bad(): void { const m = ~(\"x\" => 1); return; }");
        rejected("fnc bad(): void { const m = ~map<string,int>(\"x\" => true); return; }");
        rejected("fnc bad(): void { const m = ~map<string,int>(#ready => 2); return; }");
        rejected("fnc bad(): void { const m = ~map<string,int>(\"x\" => 1, \"x\" => 2); return; }");
        rejected("fnc bad(): void { const m = ~map<string,int>(\"x\" => get()); return; }");
        rejected("fnc bad(): void { const m = ~map<string,int>(...other); return; }");
        rejected("fnc bad(): void { const m = ~map<string,int>(\"x\" => 1, \"y\" => 3.0); return; }");
    }

    @Test
    void explicitGuestMapHasUniqueTypeWitnessAndNoHostDictionaryEscape() {
        var program = Parser.parse("""
                fnc f(): void {
                    const m = ~map<string, int>("hi" => 42);
                    return;
                }
                """);
        var function = (dev.oreslang.ast.Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        var binding = (dev.oreslang.ast.Ast.BindingStmt) function.body().getFirst();
        var map = assertInstanceOf(
                dev.oreslang.ast.Ast.MapLiteralExpr.class, binding.initializer());
        assertEquals("Map", map.mapType().name());
        assertEquals(2, map.mapType().arguments().size());
        assertEquals(1, map.entries().size());
        assertFalse(map.entries().getFirst().key() instanceof
                dev.oreslang.ast.Ast.SpreadExpr);
    }
}
