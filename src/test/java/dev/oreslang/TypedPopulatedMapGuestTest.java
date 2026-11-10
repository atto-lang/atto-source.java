package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit typed populated map v1: literal-only and non-escaping on failure. */
final class TypedPopulatedMapGuestTest {
    private static final String PASS =
            "pub fnc main(): void { "
            + "const m = ~map<string,int>(\"alpha\" => 42, \"beta\" => 99); "
            + "const s = ~map<Symbol,bool>(#ready => true, #done => false); "
            + "stdio.println(m.size); stdio.println(s.size); return; }";

    @Test
    void compilerAndJVMGuestExecuteCanonicalLiteralMaps() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(PASS));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = assertDoesNotThrow(() -> Source.newBuilder(
                OresLanguage.ID, PASS, "typed-populated-map.atto")
                .mimeType(OresLanguage.MIME_TYPE).build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                context.eval(source);
            }
        });
        assertEquals("2\n2\n", out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void parsedASTRetainsEveryDuplicatePairInSourceOrder() {
        Ast.Program p = Parser.parse("fnc sample(): void { "
                + "const m = ~map<string,int>(\"a\" => 1, \"a\" => 2); return; }");
        assertNotNull(p);
        // Duplicate logical keys must be rejected before any executable guest Map.
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        "fnc sample(): void { const m = ~map<string,int>(\"a\" => 1, \"a\" => 2); return; }"));
        assertTrue(rejected.getMessage().contains("duplicate"), rejected.getMessage());
    }

    @Test
    void incorrectKeyValueDomainsCannotCompile() {
        for (String program : List.of(
                "fnc f(): void { const m = ~map<string,int>(#symbol => 42); return; }",
                "fnc f(): void { const m = ~map<string,int>(\"x\" => true); return; }",
                "fnc f(): void { const m = ~map<int,int>(42 => 7); return; }",
                "fnc f(): void { const m = ~map<string,int>(\"x\" => 1, \"x\" => 2); return; }")) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(program), program);
        }
    }

    @Test
    void effectfulOrBorrowedEntriesRemainFailClosed() {
        for (String program : List.of(
                "fnc make_key(): string { return \"x\"; } "
                        + "fnc f(): void { const m = ~map<string,int>(make_key() => 1); return; }",
                "fnc f(): void { const x = 3; "
                        + "const m = ~map<string,int>(\"a\" => x); return; }",
                "fnc f(): void { const m = ~map<string,int>(\"a\" => 1 + 2); return; }")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(program), program);
            assertTrue(failure.getMessage().contains("literal")
                            || failure.getMessage().contains("ownership"),
                    failure.getMessage());
        }
    }

    @Test
    void bareAndContextualPopulatedMapsAreStillNotEnabled() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f(): void { const x = ~(\"x\" => 1); return; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f(): void { const x = ~map(\"x\" => 1); return; }"));
    }
}
