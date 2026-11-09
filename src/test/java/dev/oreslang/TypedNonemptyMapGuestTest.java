package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.OresKeyedMap;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler-produced Map guest path, distinct from standalone Map storage. */
final class TypedNonemptyMapGuestTest {
    private static String fixture(String file) {
        try (InputStream input = TypedNonemptyMapGuestTest.class.getResourceAsStream(
                "/atto/typed-nonempty-map/" + file)) {
            assertNotNull(input, "missing fixture " + file);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Test
    void nonemptyLiteralCompilesAndExecutesAsCanonicalGuestMap() {
        String program = fixture("nonempty-map-guest-pass.atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = assertDoesNotThrow(() -> Source.newBuilder(
                OresLanguage.ID, program, "nonempty-map-guest-pass.atto")
                .mimeType(OresLanguage.MIME_TYPE).build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                context.eval(source);
            }
        });
        assertEquals(fixture("nonempty-map-guest-pass.stdout"),
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void directlyTypedBindingInfersNonemptyMapAndExecutes() {
        String program = fixture("nonempty-map-contextual-guest-pass.atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = assertDoesNotThrow(() -> Source.newBuilder(
                OresLanguage.ID, program, "nonempty-map-contextual-guest-pass.atto")
                .mimeType(OresLanguage.MIME_TYPE).build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                context.eval(source);
            }
        });
        assertEquals(fixture("nonempty-map-contextual-guest-pass.stdout"),
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void wrongKeyAndValueTypesFailBeforeGuestExecution() {
        for (String file : List.of(
                "nonempty-map-wrong-key-reject.atto",
                "nonempty-map-wrong-value-reject.atto")) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(fixture(file)), file);
        }
    }

    @Test
    void duplicateLogicalKeysRejectDuringActualGuestExecution() {
        String program = fixture("nonempty-map-duplicate-guest-reject.atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            Exception failure = assertThrows(Exception.class,
                    () -> context.eval(Source.newBuilder(
                            OresLanguage.ID, program, "nonempty-map-duplicate-guest-reject.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build()));
            assertTrue(failure.toString().contains("duplicate logical key"),
                    failure.toString());
        }
    }

    @Test
    void storageRejectsDuplicatesAndInvalidValuesAtomically() {
        OresKeyedMap<String, Object> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        map.putDistinctEntries(List.of(Map.entry("first", 1L)));
        assertThrows(IllegalArgumentException.class, () -> map.putDistinctEntries(
                List.of(Map.entry("second", 2L), Map.entry("second", 3L))));
        assertThrows(IllegalArgumentException.class, () -> map.putDistinctEntries(
                List.of(Map.entry("second", 2L), Map.entry("third", "wrong"))));
        assertThrows(IllegalArgumentException.class, () -> map.putDistinctEntries(
                List.of(Map.entry("first", 9L))));
        assertEquals(1, map.size());
        assertEquals(1L, map.getRequired("first"));
        assertFalse(map.containsKey("second"));
        map.putDistinctEntries(List.of(Map.entry("second", 2L), Map.entry("third", 3L)));
        assertEquals(List.of("first", "second", "third"),
                map.entrySnapshot().stream().map(Map.Entry::getKey).toList());
    }

    @Test
    void invalidUnicodeAndKeyQuotaCannotPartiallyCommit() {
        OresKeyedMap<String, Object> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        assertThrows(IllegalArgumentException.class, () -> map.putDistinctEntries(
                List.of(Map.entry("valid", 1L), Map.entry("\uD800", 2L))));
        assertEquals(0, map.size());
        List<Map.Entry<String, Object>> oversized = new ArrayList<>();
        for (int i = 0; i <= OresKeyedMap.MAX_ENTRIES; i++) {
            oversized.add(Map.entry("key_" + i, (Object) (long) i));
        }
        assertThrows(IllegalArgumentException.class,
                () -> map.putDistinctEntries(oversized));
        assertEquals(0, map.size());
    }

    @Test
    void untypedAndAmbiguousMapFormsRemainRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f(): void { const m = ~map(\"x\" => 1); return; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f(): void { const m = ~map(\"x\" => 1); return; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f(): void { const m = ~(); return; }"));
    }
}
