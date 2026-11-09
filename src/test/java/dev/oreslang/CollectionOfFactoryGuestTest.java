package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The of() aliases must use the same compiler-owned aggregate AST and
 * guest runtime as the corresponding literal forms. No host Java collections
 * or dynamic property/member fallback may provide a replacement value.
 */
final class CollectionOfFactoryGuestTest {
    private static String program(String body) {
        return "pub fnc main(): void {\n" + body + "\nreturn;\n}\n";
    }

    private static String guestStdout(String text) {
        Source source = assertDoesNotThrow(() -> Source.newBuilder(
                OresLanguage.ID, text, "collection-factory-of.atto")
                .mimeType(OresLanguage.MIME_TYPE).build());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                context.eval(source);
            }
        });
        return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void mapOfAndTildeMapUseTheSameTypedGuestStorage() {
        String text = program("""
              const counts = Map.of<string, int>("alpha" => 1, "beta" => 2);
              const flags = ~map<Symbol, bool>(#ready => true);
              const more = Map.of<Symbol, bool>(#ready => true, #done => false);
              stdio.println(counts.size);
              stdio.println(flags.size);
              stdio.println(more.size);
              """);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(text));
        assertEquals("2\n1\n2\n", guestStdout(text));
    }

    @Test
    void mapOfAcceptsDirectContextAndTypedEmpty() {
        String text = program("""
              const Map<string, int> contextual = Map.of("alpha" => 1);
              const Map<Symbol, bool> empty = Map.of();
              const typed = Map.of<string, int>();
              stdio.println(contextual.size);
              stdio.println(empty.size);
              stdio.println(typed.size);
              """);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(text));
        assertEquals("1\n0\n0\n", guestStdout(text));
    }

    @Test
    void arrayTupleAndRecordFactoriesReuseExistingAggregateShapes() {
        String text = program("""
              const values = Array.of(1, 2, 3);
              const tuple = Tuple.of(7, true);
              const record = Record.of(ready: true);
              stdio.println(values[0]);
              stdio.println(tuple[0]);
              stdio.println(record.ready);
              """);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(text));
        assertEquals("1\n7\ntrue\n", guestStdout(text));
    }

    @Test
    void missingOrInvalidMapWitnessesAreNotDynamicMaps() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program("const m = Map.of();")));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program("const m = Map.of(\"key\" => 1);")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const m = Map.of<string>(\"key\" => 1);")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const m = Map.of<string, int>(\"key\": 1);")));
    }

    @Test
    void unsupportedFactoryTypeWitnessesAndMapSpreadsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const a = Array.of<int>(1, 2);")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const t = Tuple.of<int>(1);")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const m = Map.of<string,int>(...source);")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program("const r = Record.of<int>(x: 1);")));
    }
}
