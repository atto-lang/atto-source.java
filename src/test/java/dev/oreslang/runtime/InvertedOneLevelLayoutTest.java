package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One-level inversion: ONE column per directly declared field.
 * Structural-record members stay packed AoS, never split into child columns.
 */
final class InvertedOneLevelLayoutTest {
    private static Ast.ClassDecl klass(String source) {
        return (Ast.ClassDecl) Parser.parse(source).modules().getFirst().declarations().getFirst();
    }

    private static final String PARTICLE = """
            define inverted class Particle as
                position: struct{x: f64, y: f64, alive: bool};
                velocity: struct{x: f32, y: f32};
                mass: f64;
            end
            """;

    @Test
    void onlyDirectFieldsBecomeColumnsAndRecordBytesStayPacked() {
        InvertedArray.Schema schema = InvertedArray.Schema.fromClass(klass(PARTICLE));
        assertEquals(3, schema.fieldCount(), "not 3 + number of nested members");
        assertEquals("position", schema.fields().get(0).name());
        assertEquals("velocity", schema.fields().get(1).name());
        assertEquals("mass", schema.fields().get(2).name());
        assertEquals(InvertedColumnStore.Kind.RECORD, schema.fields().get(0).kind());
        assertEquals(InvertedColumnStore.Kind.RECORD, schema.fields().get(1).kind());
        assertEquals(InvertedColumnStore.Kind.F64, schema.fields().get(2).kind());
        assertEquals(17, schema.fields().get(0).recordLayout().bytes());
        assertEquals(8, schema.fields().get(1).recordLayout().bytes());
        assertThrows(IllegalArgumentException.class, () -> schema.index("position.x"));
        assertThrows(IllegalArgumentException.class, () -> schema.index("velocity.y"));

        try (InvertedArray array = InvertedArray.fromClass(klass(PARTICLE), 2)) {
            var position = Map.<String,Object>of("x", 1.0, "y", 2.0, "alive", true);
            var velocity = Map.<String,Object>of("x", 3.0f, "y", 4.0f);
            var first = array.pushValues(position, velocity, 5.0);
            var second = array.pushValues(Map.of("x", 6.0, "y", 7.0, "alive", false),
                    Map.of("x", -1.0f, "y", -2.0f), 8.0);
            assertEquals(position, array.read(first, "position"));
            assertEquals(velocity, array.read(first, "velocity"));
            assertEquals(5.0, array.read(first, "mass"));
            assertEquals(3, array.snapshot(first).size());

            assertThrows(UnsupportedOperationException.class,
                    () -> ((Map<String,Object>) array.read(first, "position")).put("x", 42.0));
            array.write(first, "position", Map.of("x", 9.0, "y", 10.0, "alive", false));
            assertEquals(Map.of("x", 9.0, "y", 10.0, "alive", false),
                    array.bindField("position").get(first));
            try (var positionLoan = array.borrowRead("position");
                 var velocityLoan = array.borrowWrite("velocity")) {
                assertEquals(Map.of("x", 9.0, "y", 10.0, "alive", false), positionLoan.readAt(0));
                velocityLoan.writeAt(1, Map.of("x", 22.0f, "y", 23.0f));
                assertThrows(IllegalStateException.class,
                        () -> array.write(first, "position", position));
            }
            assertEquals(Map.of("x", 22.0f, "y", 23.0f), array.read(second, "velocity"));
            array.eraseSwap(first);
            assertEquals(1, array.size());
            assertThrows(IllegalArgumentException.class, () -> array.read(first, "position"));
            assertEquals(8.0, array.read(second, "mass"));
        }
    }

    @Test
    void checksWholeRecordTypeAndPreservesExistingRowsOnFailure() {
        try (var array = InvertedArray.fromClass(klass(PARTICLE), 0)) {
            var goodPosition = Map.<String,Object>of("x", 1.0, "y", 2.0, "alive", true);
            var velocity = Map.<String,Object>of("x", 3.0f, "y", 4.0f);
            var handle = array.pushValues(goodPosition, velocity, 5.0);
            var before = array.snapshot(handle);
            assertThrows(IllegalArgumentException.class,
                    () -> array.pushValues(Map.of("x", 1.0, "y", 2.0), velocity, 7.0));
            assertThrows(IllegalArgumentException.class,
                    () -> array.write(handle, "position",
                            Map.of("x", 1, "y", 2.0, "alive", true)));
            assertThrows(IllegalArgumentException.class,
                    () -> array.write(handle, "position",
                            Map.of("x", 1.0, "y", 2.0, "alive", true, "z", 4.0)));
            assertThrows(IllegalArgumentException.class,
                    () -> array.updateRow(handle, Map.of(
                            "position", goodPosition, "velocity", Map.of("x", 2.0, "y", 3.0), "mass", 9.0)));
            assertEquals(before, array.snapshot(handle));
            assertEquals(1, array.size());
        }
    }

    @Test
    void namedAliasOnlyResolvesAsASingleTopLevelColumn() {
        Ast.TypeRef point = Ast.TypeRef.recordType(
                Map.of("x", Ast.TypeRef.simple("f64"), "y", Ast.TypeRef.simple("f64")));
        var source = klass("""
                define inverted class Particle as
                    position: Position;
                    mass: i32;
                end
                """);
        var schema = InvertedArray.Schema.fromClass(source, Map.of("Position", point));
        assertEquals(2, schema.fieldCount());
        assertEquals(16, schema.fields().get(0).recordLayout().bytes());
        assertThrows(IllegalArgumentException.class, () -> InvertedArray.Schema.fromClass(source));
        try (var array = InvertedArray.fromClass(source, 1, Map.of("Position", point))) {
            var h = array.pushValues(Map.of("x", 0.0, "y", 3.0), 1);
            assertEquals(Map.of("x", 0.0, "y", 3.0), array.row(h).get("position"));
        }
    }

    @Test
    void unverifiedNestedOrVariableSizedRecordsFailClosed() {
        String[] invalid = {
                "position: struct{x: struct{y: f64}};",
                "position: struct{x: string};",
                "position: struct{x: Array<i32>};",
                "position: struct{};",
                "position: struct{x: int};"
        };
        for (String field : invalid) {
            var source = "define inverted class Particle as " + field + " end";
            assertThrows(IllegalArgumentException.class,
                    () -> InvertedArray.Schema.fromClass(klass(source)), source);
        }
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(PARTICLE)),
                "host-only column support must not prematurely enable guest execution");
    }

    @Test
    void structuralRecordLayoutCanonicalizesDirectAstOrderAndIsBounded() {
        Ast.TypeRef record = Ast.TypeRef.recordType(new LinkedHashMap<>(Map.of(
                "z", Ast.TypeRef.simple("i32"),
                "a", Ast.TypeRef.simple("i32"))));
        InvertedRecordLayout layout = InvertedRecordLayout.fromType(record);
        assertEquals(java.util.List.of("a", "z"),
                layout.members().stream().map(InvertedRecordLayout.Member::name).toList());
        assertArrayEquals(new byte[] {4, 3, 2, 1, 8, 7, 6, 5},
                layout.encode(Map.of("z", 0x05060708, "a", 0x01020304)));

        // Reorder the already-canonical internal field descriptors to model a
        // direct/re-written AST. The codec must restore canonical name order.
        Ast.TypeRef directUnsorted = new Ast.TypeRef(
                record.name(), java.util.List.of(
                        record.arguments().get(1), record.arguments().get(0)), false);
        InvertedRecordLayout directLayout = InvertedRecordLayout.fromType(directUnsorted);
        assertEquals(java.util.List.of("a", "z"),
                directLayout.members().stream().map(InvertedRecordLayout.Member::name).toList());
        assertArrayEquals(new byte[] {4, 3, 2, 1, 8, 7, 6, 5},
                directLayout.encode(Map.of("z", 0x05060708, "a", 0x01020304)));

        LinkedHashMap<String, Ast.TypeRef> tooMany = new LinkedHashMap<>();
        for (int i = 0; i <= InvertedRecordLayout.MAX_RECORD_MEMBERS; i++) {
            tooMany.put("f" + i, Ast.TypeRef.simple("i32"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> InvertedRecordLayout.fromType(Ast.TypeRef.recordType(tooMany)));

        // The 1,024-member cap is currently stricter than the 64-KiB byte cap:
        // 1,024 admitted i64 members occupy 8 KiB. Exercise that real boundary
        // without pretending the future-proof byte cap is independently reachable.
        LinkedHashMap<String, Ast.TypeRef> maxCurrentWidth = new LinkedHashMap<>();
        for (int i = 0; i < InvertedRecordLayout.MAX_RECORD_MEMBERS; i++) {
            maxCurrentWidth.put("f" + i, Ast.TypeRef.simple("i64"));
        }
        assertEquals(InvertedRecordLayout.MAX_RECORD_MEMBERS * Long.BYTES,
                InvertedRecordLayout.fromType(Ast.TypeRef.recordType(maxCurrentWidth)).bytes());
    }

    @Test
    void modernTypeRefAndAliasShapesFailClosedAtSchemaBoundary() {
        Ast.ClassDecl base = klass("""
                define inverted class Shape as
                    value: i32;
                end
                """);
        java.util.function.Function<Ast.TypeRef, Ast.ClassDecl> withType = type -> {
            Ast.FieldDecl original = base.fields().getFirst();
            Ast.FieldDecl field = new Ast.FieldDecl(
                    original.name(), original.visibility(), original.bindingKind(), type,
                    original.annotations(), null, original.mutableReferent());
            return new Ast.ClassDecl(
                    base.name(), base.visibility(), base.isAbstract(), base.actorKind(),
                    base.genericParameters(), base.parents(), base.interfaces(),
                    java.util.List.of(field), base.constructor(), base.methods(),
                    base.actorConcurrency(), true);
        };

        for (Ast.TypeRef unsupported : java.util.List.of(
                Ast.TypeRef.inferred(),
                Ast.TypeRef.simple("T"),
                new Ast.TypeRef("Array", java.util.List.of(Ast.TypeRef.simple("i32")), false),
                new Ast.TypeRef("Mystery", java.util.List.of(), true),
                Ast.TypeRef.tupleType(java.util.List.of(
                        Ast.TypeRef.simple("i32"), Ast.TypeRef.simple("i32"))),
                Ast.TypeRef.union(java.util.List.of(
                        Ast.TypeRef.simple("i32"), Ast.TypeRef.simple("f64"))),
                Ast.TypeRef.borrowed(Ast.TypeRef.simple("i32"), false))) {
            assertThrows(IllegalArgumentException.class,
                    () -> InvertedArray.Schema.fromClass(withType.apply(unsupported)),
                    unsupported.toString());
        }

        // Main parses source struct{...} as a distinct closed $struct$ aggregate;
        // it uses the same fixed one-level record column layout.
        assertEquals(InvertedColumnStore.Kind.RECORD,
                InvertedArray.Schema.fromClass(withType.apply(Ast.TypeRef.structType(
                        Map.of("x", Ast.TypeRef.simple("f64"))))).fields().getFirst().kind());

        Ast.TypeRef readonlyRecord = Ast.TypeRef.readonlyRecordType(
                Map.of("x", Ast.TypeRef.simple("i32")));
        assertEquals(InvertedColumnStore.Kind.RECORD,
                InvertedArray.Schema.fromClass(withType.apply(readonlyRecord))
                        .fields().getFirst().kind());

        Ast.ClassDecl aliasField = withType.apply(Ast.TypeRef.simple("Alias"));
        assertThrows(IllegalArgumentException.class, () ->
                InvertedArray.Schema.fromClass(aliasField, Map.of(
                        "Alias", Ast.TypeRef.structType(
                                Map.of("x", Ast.TypeRef.simple("i32"))))));
        assertThrows(IllegalArgumentException.class, () ->
                InvertedArray.Schema.fromClass(aliasField, Map.of(
                        "Alias", Ast.TypeRef.readonlyRecordType(Map.of(
                                "nested", Ast.TypeRef.readonlyRecordType(
                                        Map.of("x", Ast.TypeRef.simple("i32"))))))));

        Ast.ClassDecl genericAliasUse = withType.apply(new Ast.TypeRef(
                "Alias", java.util.List.of(Ast.TypeRef.simple("i32")), false));
        assertThrows(IllegalArgumentException.class, () ->
                InvertedArray.Schema.fromClass(genericAliasUse, Map.of(
                        "Alias", readonlyRecord)));

        Ast.FieldDecl original = base.fields().getFirst();
        Ast.FieldDecl annotated = new Ast.FieldDecl(
                original.name(), original.visibility(), original.bindingKind(),
                original.type(), java.util.List.of(
                        new Ast.Annotation("layout", java.util.List.of())),
                null, original.mutableReferent());
        Ast.ClassDecl annotatedClass = new Ast.ClassDecl(
                base.name(), base.visibility(), base.isAbstract(), base.actorKind(),
                base.genericParameters(), base.parents(), base.interfaces(),
                java.util.List.of(annotated), base.constructor(), base.methods(),
                base.actorConcurrency(), true);
        assertThrows(IllegalArgumentException.class,
                () -> InvertedArray.Schema.fromClass(annotatedClass));
    }

    @Test
    void sharedJavaLlvmFixtureLocksExactPackedBytesAndSignedZero() {
        Ast.TypeRef positionType = Ast.TypeRef.recordType(Map.of(
                "alive", Ast.TypeRef.simple("bool"),
                "x", Ast.TypeRef.simple("f64"),
                "y", Ast.TypeRef.simple("f64")));
        InvertedRecordLayout layout = InvertedRecordLayout.fromType(positionType);
        byte[] expected = {
                1,
                0, 0, 0, 0, 0, 0, (byte) 0xf0, 0x3f,
                0, 0, 0, 0, 0, 0, 0, 0x40
        };
        assertEquals(java.util.List.of("alive", "x", "y"),
                layout.members().stream().map(InvertedRecordLayout.Member::name).toList());
        assertEquals(17, layout.bytes());
        assertArrayEquals(expected, layout.encode(Map.of("alive", true, "x", 1.0, "y", 2.0)));
        assertEquals(Map.of("alive", true, "x", 1.0, "y", 2.0), layout.decode(expected));

        byte[] signedZero = layout.encode(Map.of("alive", false, "x", -0.0, "y", 9.0));
        long xBits = java.nio.ByteBuffer.wrap(signedZero, 1, Long.BYTES)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
        assertEquals(Double.doubleToRawLongBits(-0.0), xBits);
    }

    @Test
    void rejectsNoncanonicalBooleanBytesAtRecordDecodeBoundary() {
        Ast.TypeRef valueType = Ast.TypeRef.recordType(
                Map.of("enabled", Ast.TypeRef.simple("bool")));
        InvertedRecordLayout layout = InvertedRecordLayout.fromType(valueType);
        assertEquals(Map.of("enabled", false), layout.decode(new byte[] {0}));
        assertEquals(Map.of("enabled", true), layout.decode(new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> layout.decode(new byte[] {2}));
        assertThrows(IllegalArgumentException.class, () -> layout.decode(new byte[] {-1}));
        assertThrows(IllegalArgumentException.class, () -> layout.decode(new byte[0]));
    }

    @Test
    void recordPayloadsAreDefensivelyCopiedAtPhysicalStoreBoundary() {
        try (var store = new InvertedColumnStore(InvertedColumnStore.ColumnSpec.record(4))) {
            byte[] original = {1, 2, 3, 4};
            var handle = store.push(original);
            original[0] = 9;
            byte[] snapshot = (byte[]) store.get(handle, 0);
            assertArrayEquals(new byte[] {1, 2, 3, 4}, snapshot);
            snapshot[0] = 8;
            assertArrayEquals(new byte[] {1, 2, 3, 4}, (byte[]) store.get(handle, 0));
            assertThrows(IllegalArgumentException.class, () -> store.set(handle, 0, new byte[3]));
            try (var loan = store.borrowWrite(0)) {
                byte[] value = (byte[]) loan.get(0);
                value[0] = 11;
                assertArrayEquals(new byte[] {1, 2, 3, 4}, (byte[]) loan.get(0));
                loan.set(0, new byte[] {5, 6, 7, 8});
            }
            assertArrayEquals(new byte[] {5, 6, 7, 8}, (byte[]) store.get(handle, 0));
        }
    }
}
