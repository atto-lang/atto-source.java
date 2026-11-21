package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Guest E2E targets for canonical Map.of; the separately stacked #2735 lowering is a merge gate. */
final class NonemptyTildeMapGuestTest {
    private static String fixture(String file) {
        try (InputStream input = NonemptyTildeMapGuestTest.class.getResourceAsStream(
                "/atto/tilde-map/" + file)) {
            assertNotNull(input, "missing fixture " + file);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void runAndVerify(String name) {
        String sourceText = fixture(name + ".atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(sourceText));
        Source source = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, sourceText, name + ".atto")
                        .mimeType(OresLanguage.MIME_TYPE).build());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                context.eval(source);
            }
        });
        assertEquals(fixture(name + ".stdout"),
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void explicitScalarMapsCompileAndExecuteWithTwoEntries() {
        runAndVerify("nonempty-explicit-guest");
    }

    @Test
    void directlyContextTypedMapAndShorthandCompileAndExecute() {
        runAndVerify("nonempty-contextual-guest");
    }

    @Test
    void duplicateStringAndSymbolKeysFailAtomicallyAtRuntime() {
        for (String name : new String[]{
                "nonempty-duplicate-key-reject",
                "nonempty-duplicate-symbol-reject"}) {
            String text = fixture(name + ".atto");
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(text), name);
            Source source = assertDoesNotThrow(() ->
                    Source.newBuilder(OresLanguage.ID, text, name + ".atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                RuntimeException failure = assertThrows(RuntimeException.class,
                        () -> context.eval(source), name);
                assertTrue(String.valueOf(failure.getMessage())
                                .contains("duplicate logical key"),
                        name + ": " + failure.getMessage());
            }
        }
    }

    @Test
    void mismatchedStaticallyTypedKeyAndValueAreCompileFailures() {
        for (String name : new String[]{
                "nonempty-wrong-value-reject",
                "nonempty-wrong-key-reject"}) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(fixture(name + ".atto")), name);
            String expected = name.contains("value")
                    ? "Map literal value" : "Map literal key";
            assertTrue(failure.getMessage().contains(expected),
                    "must fail the actual Map.of type gate, not unrelated parsing: "
                            + name + ": " + failure.getMessage());
        }
    }

    @Test
    void missingWitnessSpreadAndRecordSyntaxCannotBypassAdmission() {
        IllegalArgumentException untyped = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        fixture("nonempty-untyped-reject.atto")));
        assertTrue(untyped.getMessage().contains("Map<K,V>"),
                "missing Map witness must fail the type gate: " + untyped.getMessage());
        for (String name : new String[]{
                "nonempty-spread-reject",
                "nonempty-mixed-reject"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(fixture(name + ".atto")), name);
        }
    }

    @Test
    void removedTildeMapAliasesRejectBeforeGuestAllocation() {
        for (String old : new String[]{
                "~map<string,int>(\"a\" => 1)",
                "~map(\"a\" => 1)",
                "~map()",
                "~<Map<string,int>>()",
                "~(\"a\" => 1)"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("fnc read(): void { const m = " + old + "; return; }"), old);
        }
    }

    @Test
    void mapLiteralRejectsOverBoundBeforeAnyBackendAllocation() {
        StringBuilder src = new StringBuilder(
                "fnc read(): void { const m = Map.of<string,int>(");
        for (int i = 0; i <= 4096; i++) {
            if (i != 0) src.append(", ");
            src.append("\"key_").append(i).append("\" => ").append(i);
        }
        src.append("); return; }");
        IllegalArgumentException problem = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(src.toString()));
        assertTrue(problem.getMessage().contains("4096 entries"),
                problem.getMessage());
    }
}
