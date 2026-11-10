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
 * Compiler-owned, effect-free, populated Map<K,V> first slice.
 * Not a general dynamic/map-spread or cross-actor transport implementation.
 */
final class TypedNonemptyMapGuestTest {
    private static String source(String entries, String keyType, String valueType) {
        return "pub fnc main(): void { const m = ~map<" + keyType + ","
                + valueType + ">(" + entries
                + "); stdio.println(m.size); return; }";
    }

    @Test
    void scalarStringKeysExecuteInRealTruffleGuest() {
        String program = source("\"ready\" => 42, \"done\" => 7,", "string", "int");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source guest = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, program, "typed-populated-map.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build());
        assertDoesNotThrow(() -> {
            try (Context ctx = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                ctx.eval(guest);
            }
        });
        assertEquals("2\n", out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void symbolKeysAndTypedBoolValuesExecuteWithoutStringCoercion() {
        String program = source("#ready => true, #done => false", "Symbol", "bool");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source guest = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, program, "typed-symbol-map.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build());
        assertDoesNotThrow(() -> {
            try (Context ctx = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                ctx.eval(guest);
            }
        });
        assertEquals("2\n", out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void duplicateLogicalKeysAndWrongScalarDomainsFailTypeAdmission() {
        for (String program : new String[]{
                source("\"dup\" => 1, \"dup\" => 2", "string", "int"),
                source("#ready => true, #ready => false", "Symbol", "bool"),
                source("#ready => 1", "string", "int"),
                source("\"ready\" => true", "string", "int"),
                source("\"ready\" => 1", "Symbol", "int"),
                source("\"ready\" => 1", "string", "float"),
                source("\"ready\" => 1", "float", "int")}) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(program), program);
        }
    }

    @Test
    void computedKeysValuesSpreadsAndLegacyMapShortFormRemainRejected() {
        for (String program : new String[]{
                source("some_key => 1", "string", "int"),
                source("\"ready\" => compute()", "string", "int"),
                source("...\"ready\" => 1", "string", "int"),
                source("\"ready\" => 1, ...other", "string", "int"),
                "fnc f(): void { const m = ~(\"ready\" => 1); return; }"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(program), program);
        }
    }

    @Test
    void actorCallableCannotCarryMutableTypedMapWithoutSendProof() {
        String program = """
                define actor Worker as
                    pub receive(Map<string,int> payload): void { return; }
                end
                """;
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(rejected.getMessage().contains("Map"), rejected.getMessage());
    }

    @Test
    void noUnverifiedHostMapMemberAccess() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        "fnc f(): void { const m = ~map<string,int>(\"x\" => 1); "
                                + "const x = m.get(\"x\"); return; }"));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        "fnc f(): void { const m = ~map<string,int>(\"x\" => 1); "
                                + "m.put(\"x\", 2); return; }"));
    }
}
