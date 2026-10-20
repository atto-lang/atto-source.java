package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TypedMapGuestLanguageTest {
    private static String execute(String program) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "typed-map-guest.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void rejectTypes(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)), source);
    }

    private static void rejectExecutable(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)), source);
    }

    @Test void symbolMapWritesReadsAndDirectOperationsExecute() throws Exception {
        String program = """
                pub routine main(): void {
                    val m = new Map<Symbol, int>();
                    m[#ready] = 41;
                    m[#ready] = 42;
                    if m.has(#ready) && (m[#ready] eq 42) then
                        stdio.stdout.write("present");
                    fi
                    if m.remove(#ready) && !m.has(#ready) then
                        stdio.stdout.write("-removed");
                    fi
                    return;
                }
                """;
        assertEquals("present-removed", execute(program));
    }

    @Test void mixedStringAndSymbolKeysRemainNominallyDistinct() throws Exception {
        String program = """
                pub routine main(): void {
                    val m = new Map<Symbol | String, int>();
                    m[#ready] = 5;
                    m["ready"] = 9;
                    if (m[#ready] eq 5) && (m["ready"] eq 9) then
                        stdio.stdout.write("separate");
                    fi
                    return;
                }
                """;
        assertEquals("separate", execute(program));
    }

    @Test void getAndTakeHavePresenceAwareOptionTypes() {
        String program = """
                pub fnc ok(): void {
                    val m = new Map<String, int>();
                    m["x"] = 7;
                    val Option<int> seen = m.get("x");
                    val Option<int> moved = m.take("x");
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
    }

    @Test void wrongKeyValueAndDomainShapesFailClosed() {
        String[] invalid = {
            "pub fnc bad(): void { val m = new Map<Symbol, int>(); m[\"ready\"] = 3; return; }",
            "pub fnc bad(): void { val m = new Map<Symbol, int>(); m[#ready] = \"bad\"; return; }",
            "pub fnc bad(): void { val m = new Map<int, int>(); return; }",
            "pub fnc bad(): void { val m = new Map<Symbol>(); return; }",
            "pub fnc bad(): void { val m = new Map<Symbol, float>(); return; }",
            "pub fnc bad(): void { val m = new Map<Symbol | int, int>(); return; }",
            "pub fnc bad(): void { val m = new Map<Symbol, int>(1); return; }"
        };
        for (String source : invalid) rejectTypes(source);
    }

    @Test void immutableBindingsCannotMutateOrConsumeMap() {
        String[] invalid = {
            "pub fnc bad(): void { const m = new Map<String, int>(); m[\"x\"] = 1; return; }",
            "pub fnc bad(): void { let m = new Map<String, int>(); m[\"x\"] = 1; return; }",
            "pub fnc bad(): void { const m = new Map<String, int>(); val x = m.take(\"x\"); return; }",
            "pub fnc bad(): void { let m = new Map<String, int>(); val x = m.remove(\"x\"); return; }"
        };
        for (String source : invalid) rejectExecutable(source);
    }

    @Test void aliasesRebindingReturnsAndCapturesAreRejected() {
        String[] invalid = {
            "pub fnc bad(): void { val m = new Map<String, int>(); const alias = m; return; }",
            "pub fnc bad(): void { let mut m = new Map<String, int>(); let mut alias = m; return; }",
            "pub fnc bad(): void { let mut m = new Map<String, int>(); m = new Map<String, int>(); return; }",
            "pub fnc bad(): Map<String, int> { val m = new Map<String, int>(); return m; }",
            "pub fnc bad(): void { val m = new Map<String, int>(); val f = () -> { const x = m.has(\"x\"); }; return; }"
        };
        for (String source : invalid) rejectExecutable(source);
    }

    @Test void mapsCannotEnterUntrackedContainersOrLongLivedStorage() {
        String[] invalid = {
            "pub fnc bad(): void { val m = new Map<String, int>(); val xs = [m]; return; }",
            "pub fnc bad(): void { val m = new Map<String, int>(); val xs = (m,); return; }",
            """
            define class Holder as
                val Map<String, int> map;
            end
            """,
            "val Map<String, int> global = new Map<String, int>();"
        };
        for (String source : invalid) rejectTypes(source);
    }

    @Test void mapOperationsCannotBeExtractedAsFirstClassMembers() {
        String[] invalid = {
            "pub fnc bad(): void { val m = new Map<String, int>(); val f = m.get; return; }",
            "pub fnc bad(): void { val m = new Map<String, int>(); val f = m.take; return; }",
            "pub fnc bad(): void { val m = new Map<String, int>(); val x = m.ready; return; }"
        };
        for (String source : invalid) rejectTypes(source);
    }

    @Test void ordinaryCallArgumentsCannotConsumeTypedMaps() {
        String source = """
                fnc sink(Map<String, int> m): void { return; }
                pub fnc bad(): void {
                    val m = new Map<String, int>();
                    sink(m);
                    return;
                }
                """;
        rejectExecutable(source);
    }
}
