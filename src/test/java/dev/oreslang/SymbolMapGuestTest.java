package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.OresGuestMap;
import dev.oreslang.runtime.OresSymbol;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class SymbolMapGuestTest {
    @Test
    void guestSymbolKeyedMapAndLegacyStructRunWithoutAliasing() throws Exception {
        String program = """
                pub fnc main(): void {
                  let m: Map<Symbol, int> = new Map<Symbol, int>();
                  m[#foo] = 42;
                  let answer: int = m[#foo];
                  let object = infer struct{foo: 1, status: #ready};
                  stdio.stdout.write(answer);
                  stdio.stdout.write("|");
                  stdio.stdout.write(object.foo);
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "symbol-map.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42|1", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void guestStringAndSymbolKeysRequireExplicitUnionMap() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                fnc accept(): void {
                  let m: Map<String | Symbol, int> = new Map<String | Symbol, int>();
                  m["foo"] = 1;
                  m[#foo] = 2;
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse("""
                fnc reject(): void {
                  let m: Map<String, int> = new Map<String, int>();
                  m[#foo] = 2;
                  return;
                }
                """)));
    }

    @Test
    void legacyRecordNamesCannotBeAccessedUsingSymbolKeys() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse("""
                fnc reject(): int {
                  let s = infer struct{foo: 1};
                  return s[#foo];
                }
                """)));
    }

    @Test
    void runtimeMapGuardsWrongKeyTypesAndMutableEntryViews() {
        OresGuestMap map = new OresGuestMap(OresGuestMap.KeyDomain.SYMBOL);
        map.put(OresSymbol.process("foo"), 5);
        assertEquals(5, map.get(OresSymbol.process("foo")));
        assertThrows(IllegalArgumentException.class, () -> map.put("foo", 5));
        assertThrows(IllegalArgumentException.class, () -> map.containsKey("foo"));
        assertThrows(UnsupportedOperationException.class,
                () -> map.entrySet().iterator().next().setValue(8));
    }
}
