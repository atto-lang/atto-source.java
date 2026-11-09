package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** First runnable Java/Graal slice of the tilde literal contract: records only. */
final class TildeRecordLiteralGuestTest {
    private static String checkedProgram(String expression) {
        return """
                pub fnc main(): void {
                    const row = %s;
                    stdio.println(row.name);
                    return;
                }
                """.formatted(expression);
    }

    @Test
    void shortAndExplicitRecordsCompileAndExecuteAsReadonlyStructuralValues() {
        String program = """
                pub fnc main(): void {
                    const first = ~(name: "Ada", age: 37);
                    const second = ~record(name: "Grace", active: true,);
                    stdio.println(first.name);
                    stdio.println(second.name);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = assertDoesNotThrow(() -> Source.newBuilder(
                OresLanguage.ID, program, "tilde-record-guest.atto")
                .mimeType(OresLanguage.MIME_TYPE).build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                context.eval(source);
            }
        });
        assertEquals("Ada\nGrace\n",
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void nestedRecordsAndQuotedStaticFieldsRemainStructural() {
        String program = """
                fnc read(): int {
                    const row = ~(meta: ~record(age: 37), "first name": "Ada");
                    return row.meta.age;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
    }

    @Test
    void readonlyRecordsCannotRegainMutationAuthority() {
        String source = """
                fnc bad(): void {
                    const row = ~(age: 37);
                    row.age = 38;
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void duplicateAndMixedEntriesFailBeforeRuntime() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(checkedProgram("~(name: \"A\", name: \"B\")")));
        IllegalArgumentException mixed = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(checkedProgram("~(name: \"A\", age => 3)")));
        assertTrue(mixed.getMessage().contains("mixed record"), mixed.getMessage());
    }

    @Test
    void emptyAndDynamicKeyAmbiguitiesFailClosed() {
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(checkedProgram("~()")));
        assertTrue(empty.getMessage().contains("ambiguous"), empty.getMessage());

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                fnc read(): void {
                    const empty_row = ~record();
                    return;
                }
                """));

        for (String expression : new String[] {
                "~(\"ready\" => true)", "~(#ready => true)",
                "~map(\"ready\" => true)", "~(key => true)",
                "~((1 + 2) => true)", "~(key.parts[0] => true)",
                "~map<string, int>()"
        }) {
            IllegalArgumentException diagnostic = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse(checkedProgram(expression)), expression);
            assertTrue(diagnostic.getMessage().contains("typed Map<K,V>"),
                    diagnostic.getMessage());
        }
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(checkedProgram("~(...source)")));
    }

    @Test
    void unaryTildeStillParsesIndependentlyOfAggregateSyntax() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc complement(): int {
                    return ~(1);
                }
                """));
    }

    @Test
    void unsupportedSpreadStillFailsInTheOwnershipTypeGate() {
        String program = """
                fnc read(): void {
                    const base = ~(name: "Ada");
                    const row = ~record(...base);
                    return;
                }
                """;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(e.getMessage().contains("aggregate spread"), e.getMessage());
    }
}
