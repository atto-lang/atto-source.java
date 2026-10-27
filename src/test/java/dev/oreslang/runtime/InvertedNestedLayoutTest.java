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
    void nestedImmutableRecordsArePackedOneColumnWithDetachedDeepSnapshots() {
        Ast.ClassDecl packet = inverted("""
                pose: record{location: record{x: i64, y: f64}, ready: bool};
                tag: i64;
                """);
        var schema = InvertedArray.Schema.fromClass(packet);
        assertEquals(2, schema.fieldCount(),
                "nested immutable values never become additional physical columns");
        var layout = schema.fields().getFirst().recordLayout();
        assertEquals(InvertedColumnStore.Kind.RECORD, layout.members().get(1).kind());
        assertEquals(17, layout.bytes());
        try (var array = InvertedArray.fromClass(packet, 1)) {
            var location = new java.util.LinkedHashMap<String, Object>();
            location.put("x", 42L);
            location.put("y", -0.0);
            var pose = new java.util.LinkedHashMap<String, Object>();
            pose.put("location", location);
            pose.put("ready", true);
            var handle = array.pushValues(pose, 5L);
            location.put("x", 77L);
            pose.put("ready", false);
            @SuppressWarnings("unchecked")
            Map<String,Object> read = (Map<String,Object>) array.read(handle, "pose");
            assertEquals(true, read.get("ready"));
            @SuppressWarnings("unchecked")
            Map<String,Object> readLocation = (Map<String,Object>) read.get("location");
            assertEquals(42L, readLocation.get("x"));
            assertEquals(Double.doubleToRawLongBits(-0.0),
                    Double.doubleToRawLongBits((Double) readLocation.get("y")));
            assertThrows(UnsupportedOperationException.class,
                    () -> readLocation.put("x", 1L));
            assertThrows(IllegalArgumentException.class,
                    () -> array.write(handle, "pose",
                            Map.of("ready", true, "location", Map.of("x", "bad", "y", 1.0))));
            assertEquals(42L, ((Map<?, ?>) ((Map<?, ?>) array.read(handle, "pose"))
                    .get("location")).get("x"));
            array.write(handle, "pose", Map.of("ready", false,
                    "location", Map.of("x", 100L, "y", 2.0)));
            assertEquals(100L, ((Map<?, ?>) ((Map<?, ?>) array.read(handle, "pose"))
                    .get("location")).get("x"));
        }
    }

    @Test
    void nestedValueRecordsRemainUnadmittedToGuestWithoutOwnershipProof() {
        String source = """
                define inverted class Packet as
                    pose: record{location: record{x: i64}, ready: bool};
                end
                """;
        assertDoesNotThrow(() -> InvertedArray.Schema.fromClass(
                (Ast.ClassDecl) Parser.parse(source).modules().getFirst()
                        .declarations().getFirst()));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)),
                "host record encoding does not constitute guest nested record lowering");
    }

    @Test
    void invalidRecordUpdatesRollBackAndNeverAliasGuestMaps() {
        Ast.ClassDecl packet = inverted(
                "pose: record{ready: bool, x: i64};");
        try (var array = InvertedArray.fromClass(packet, 1)) {
            var input = new java.util.LinkedHashMap<String, Object>(
                    Map.of("ready", true, "x", 10L));
            var row = array.pushValues(input);
            input.put("x", 999L);
            var field = array.bindField("pose");
            assertEquals(10L, ((Map<?, ?>) field.getAt(0)).get("x"));
            assertThrows(IllegalArgumentException.class,
                    () -> field.setAt(0, Map.of("x", 20L)));
            assertThrows(IllegalArgumentException.class,
                    () -> array.write(row, "pose", Map.of("ready", 1, "x", 20L)));
            assertThrows(IllegalArgumentException.class,
                    () -> array.updateRow(row,
                            Map.of("pose", Map.of("ready", false, "x", "bad"))));
            assertEquals(10L, ((Map<?, ?>) array.read(row, "pose")).get("x"));
            assertEquals(true, ((Map<?, ?>) array.read(row, "pose")).get("ready"));
            try (var readLoan = array.borrowRead("pose")) {
                assertThrows(IllegalStateException.class,
                        () -> field.setAt(0, Map.of("ready", false, "x", 20L)));
                assertEquals(10L, ((Map<?, ?>) readLoan.readAt(0)).get("x"));
            }
            field.setAt(0, Map.of("ready", false, "x", 20L));
            assertEquals(20L, ((Map<?, ?>) array.read(row, "pose")).get("x"));
        }
    }

    @Test
    void randomizedPackedRecordSwapDeletePreservesOpaqueRowHandles() {
        Ast.ClassDecl packet = inverted("pose: record{ready: bool, x: i64};");
        var rng = new java.util.Random(0x51A0B1L);
        try (var array = InvertedArray.fromClass(packet, 0)) {
            var handles = new java.util.ArrayList<InvertedColumnStore.Handle>();
            var values = new java.util.ArrayList<Long>();
            var column = array.bindField("pose");
            for (int i = 0; i < 1000; i++) {
                if (handles.isEmpty() || rng.nextInt(3) != 0) {
                    long value = i * 73L + 5;
                    handles.add(array.pushValues(
                            Map.of("ready", (i & 1) == 0, "x", value)));
                    values.add(value);
                } else {
                    int erased = rng.nextInt(handles.size());
                    var stale = handles.get(erased);
                    array.eraseSwap(stale);
                    int last = handles.size() - 1;
                    handles.set(erased, handles.get(last));
                    values.set(erased, values.get(last));
                    handles.remove(last);
                    values.remove(last);
                    assertThrows(IllegalArgumentException.class,
                            () -> array.read(stale, "pose"));
                }
                assertEquals(handles.size(), array.size());
                for (int j = 0; j < handles.size(); j++) {
                    assertEquals(values.get(j),
                            ((Map<?, ?>) array.read(handles.get(j), "pose")).get("x"));
                    assertEquals(values.get(j),
                            ((Map<?, ?>) column.getAt(j)).get("x"));
                }
            }
        }
    }

    @Test
    void packedRecordCodecIsDeterministicAndRejectsCorruptBoolOrWrongShapes() {
        Ast.TypeRef shape = Ast.TypeRef.recordType(Map.of(
                "x", Ast.TypeRef.simple("i64"),
                "ready", Ast.TypeRef.simple("bool"),
                "y", Ast.TypeRef.simple("f64")));
        var codec = InvertedRecordLayout.fromType(shape);
        assertEquals(17, codec.bytes());
        assertEquals("ready", codec.members().getFirst().name());
        byte[] encoded = codec.encode(Map.of("y", 1.25,
                "ready", true, "x", 0x0102030405060708L));
        var view = java.nio.ByteBuffer.wrap(encoded)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, encoded[0]);
        assertEquals(0x0102030405060708L, view.getLong(1));
        assertEquals(1.25, view.getDouble(9));
        assertEquals(0x0102030405060708L, codec.decode(encoded).get("x"));
        encoded[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(encoded));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(Map.of("ready", true, "x", 1, "y", 1.25)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(Map.of("ready", true, "x", 1L)));
    }

    @Test
    void directRecordAliasesSupportPackedNestedValuesButNotImplicitNamedAliases() {
        Ast.Program program = Parser.parse("""
                type Position = record{x: i64, y: f64};
                define inverted class Packet as pose: Position; end
                """);
        var alias = (Ast.TypeAliasDecl) program.modules().getFirst()
                .declarations().getFirst();
        var packet = (Ast.ClassDecl) program.modules().getFirst()
                .declarations().get(1);
        assertThrows(IllegalArgumentException.class,
                () -> InvertedArray.Schema.fromClass(packet));
        var aliases = Map.of("Position", alias.target());
        var schema = InvertedArray.Schema.fromClass(packet, aliases);
        assertEquals(InvertedColumnStore.Kind.RECORD, schema.fields().getFirst().kind());
        try (var array = InvertedArray.fromClass(packet, 0, aliases)) {
            var row = array.pushValues(Map.of("x", 10L, "y", 2.0));
            assertEquals(10L, ((Map<?, ?>) array.read(row, "pose")).get("x"));
        }
        var inlineNested = InvertedRecordLayout.fromType(Ast.TypeRef.recordType(
                Map.of("child", alias.target())));
        assertEquals(16, inlineNested.bytes());
        assertEquals(10L, ((Map<?, ?>) inlineNested.decode(
                inlineNested.encode(Map.of("child", Map.of("x", 10L, "y", 2.0))))
                .get("child")).get("x"));
        // The nested NAME itself is not recursively alias-expanded.
        assertThrows(IllegalArgumentException.class,
                () -> InvertedRecordLayout.fromType(Ast.TypeRef.recordType(
                        Map.of("child", Ast.TypeRef.simple("Position")))));
        assertThrows(IllegalArgumentException.class,
                () -> InvertedArray.Schema.fromClass(packet,
                        Map.of("Position", Ast.TypeRef.structType(
                                Map.of("x", Ast.TypeRef.simple("i64"))))));
    }

    @Test
    void oversizedRecordShapesAndExcessiveRecordNestingFailClosed() {
        var tooMany = new java.util.LinkedHashMap<String, Ast.TypeRef>();
        for (int i = 0; i <= InvertedRecordLayout.MAX_RECORD_MEMBERS; i++) {
            tooMany.put("field_" + i, Ast.TypeRef.simple("i64"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> InvertedRecordLayout.fromType(Ast.TypeRef.recordType(tooMany)));
        var twoLevels = Ast.TypeRef.recordType(Map.of(
                "inner", Ast.TypeRef.recordType(Map.of(
                        "x", Ast.TypeRef.simple("i64")))));
        assertEquals(8, InvertedRecordLayout.fromType(twoLevels).bytes());
        Ast.TypeRef deeper = Ast.TypeRef.recordType(
                Map.of("x", Ast.TypeRef.simple("i64")));
        for (int depth = 0; depth <= InvertedRecordLayout.MAX_RECORD_DEPTH; depth++) {
            deeper = Ast.TypeRef.recordType(Map.of("inner", deeper));
        }
        final Ast.TypeRef tooDeep = deeper;
        assertThrows(IllegalArgumentException.class,
                () -> InvertedRecordLayout.fromType(tooDeep));
        Ast.TypeRef nestedStruct = Ast.TypeRef.recordType(Map.of("inner",
                Ast.TypeRef.structType(Map.of(
                        "x", Ast.TypeRef.simple("i64")))));
        assertThrows(IllegalArgumentException.class,
                () -> InvertedRecordLayout.fromType(nestedStruct));
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
