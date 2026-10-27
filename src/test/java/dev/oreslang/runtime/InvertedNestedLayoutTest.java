package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class InvertedNestedLayoutTest {
    private static Ast.ClassDecl inverted(String fields) {
        return (Ast.ClassDecl) Parser.parse(
                "define inverted class Packet as " + fields + " end")
                .modules().getFirst().declarations().getFirst();
    }

    @Test
    void oneLevelImmutableRecordColumnSurvivesSwapDelete() {
        Ast.ClassDecl packet = inverted(
                "pose: record{y: f64, ready: bool, x: i64}; tag: i64;");
        var schema = InvertedArray.Schema.fromClass(packet);
        assertEquals(InvertedColumnStore.Kind.RECORD, schema.fields().getFirst().kind());
        assertEquals(17, schema.fields().getFirst().recordLayout().bytes());
        try (var array = InvertedArray.fromClass(packet, 0)) {
            var first = array.pushValues(Map.of("x", 1L, "y", -0.0, "ready", true), 7L);
            var second = array.pushValues(Map.of("x", 2L, "y", 3.5, "ready", false), 8L);
            var field = array.bindField("pose");
            assertEquals(1L, ((Map<?, ?>) field.getAt(0)).get("x"));
            assertThrows(UnsupportedOperationException.class,
                    () -> ((Map<String, Object>) field.getAt(0)).put("x", 99L));
            array.eraseSwap(first);
            assertEquals(2L, ((Map<?, ?>) field.getAt(0)).get("x"));
            assertEquals(2L, ((Map<?, ?>) array.read(second, "pose")).get("x"));
            assertThrows(IllegalArgumentException.class, () -> array.read(first, "pose"));
        }
    }

    @Test
    void structAndNestedInvertedOwnersRemainFailClosed() {
        var record = inverted("item: record{x: i64, ready: bool};");
        var struct = inverted("item: struct{x: i64, ready: bool};");
        assertTrue(record.fields().getFirst().type().isRecordType());
        assertTrue(struct.fields().getFirst().type().isStructType());
        assertDoesNotThrow(() -> InvertedArray.Schema.fromClass(record));
        assertThrows(IllegalArgumentException.class,
                () -> InvertedArray.Schema.fromClass(struct));

        for (String nested : new String[]{
                "Inner", "InvertedArray<Inner>",
                "record{inner: record{x: i64}}",
                "record{inner: struct{x: i64}}"
        }) {
            String source = "define inverted class Inner as id: i64; end "
                    + "define inverted class Outer as nested: " + nested + "; end";
            var program = Parser.parse(source);
            var outer = (Ast.ClassDecl) program.modules().getFirst().declarations().get(1);
            assertThrows(IllegalArgumentException.class,
                    () -> InvertedArray.Schema.fromClass(outer), nested);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), nested);
        }
    }

    @Test
    void noImplicitInvertedStructOrRecordDeclarationDesugaring() {
        for (String source : new String[]{
                "define inverted struct Packet as x: i64; end",
                "define inverted record Packet as x: i64; end"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }
}
