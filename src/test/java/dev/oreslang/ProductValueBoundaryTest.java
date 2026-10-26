package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProductValueBoundaryTest {
    @Test
    void recordMetadataPreservesSourceOrderWithoutMakingOrderTypeIdentity() {
        LinkedHashMap<String, Types.Type> first = new LinkedHashMap<>();
        first.put("c", Types.Primitive.BOOL);
        first.put("a", Types.Primitive.INT);
        first.put("b", Types.Primitive.STRING);

        LinkedHashMap<String, Types.Type> second = new LinkedHashMap<>();
        second.put("b", Types.Primitive.STRING);
        second.put("c", Types.Primitive.BOOL);
        second.put("a", Types.Primitive.INT);

        Types.Record left = new Types.Record(
                first, true, Types.AggregateKind.RECORD);
        Types.Record right = new Types.Record(
                second, true, Types.AggregateKind.RECORD);

        assertEquals(List.of("c", "a", "b"), left.members().keySet().stream().toList());
        assertEquals(List.of("b", "c", "a"), right.members().keySet().stream().toList());
        assertEquals(left, right);
        assertThrows(
                UnsupportedOperationException.class,
                () -> left.members().put("x", Types.Primitive.INT));
    }

    @Test
    void tupleRecordStructAndMapRemainSeparateTypeDomains() {
        Types.Tuple tuple = new Types.Tuple(
                List.of(Types.Primitive.INT, Types.Primitive.STRING));
        Types.Record record = new Types.Record(
                new LinkedHashMap<>(java.util.Map.of(
                        "x", Types.Primitive.INT,
                        "label", Types.Primitive.STRING)),
                true,
                Types.AggregateKind.RECORD);
        Types.Record struct = Types.Record.struct(
                new LinkedHashMap<>(java.util.Map.of(
                        "x", Types.Primitive.INT,
                        "label", Types.Primitive.STRING)));
        Types.Named map = new Types.Named(
                "Map", List.of(Types.Primitive.STRING, Types.Primitive.INT));

        assertFalse(Types.isAssignable(record, struct));
        assertFalse(Types.isAssignable(struct, record));
        assertFalse(Types.isAssignable(map, record));
        assertFalse(Types.isAssignable(record, map));
        assertFalse(Types.isAssignable(tuple, record));
        assertFalse(Types.isAssignable(record, tuple));
    }

    @Test
    void recordsRetainRowCompatibilityButStructsRequireExactClosedShape() {
        LinkedHashMap<String, Types.Type> narrowFields = new LinkedHashMap<>();
        narrowFields.put("x", Types.Primitive.INT);
        LinkedHashMap<String, Types.Type> wideFields = new LinkedHashMap<>();
        wideFields.put("x", Types.Primitive.INT);
        wideFields.put("label", Types.Primitive.STRING);

        Types.Record narrowRecord =
                new Types.Record(narrowFields, true, Types.AggregateKind.RECORD);
        Types.Record wideRecord =
                new Types.Record(wideFields, true, Types.AggregateKind.RECORD);
        Types.Record narrowStruct = Types.Record.struct(narrowFields);
        Types.Record wideStruct = Types.Record.struct(wideFields);

        assertTrue(Types.isAssignable(wideRecord, narrowRecord));
        assertFalse(Types.isAssignable(wideStruct, narrowStruct));
        assertFalse(Types.isAssignable(narrowStruct, wideStruct));
    }

    @Test
    void returnedTupleRecordAndConcreteStructDestructureOnTheirOwnAxes() {
        String program = """
                fnc tuple_result(): [int, string] {
                  return (7, "tuple");
                }

                fnc record_result(): record{x: int, label: string} {
                  return record{x: 8, label: "record"};
                }

                fnc struct_result(): struct{x: int, label: string} {
                  return struct{x: int, label: string}{x: 9, label: "struct"};
                }

                pub fnc main(): void {
                  const [tuple_number, tuple_label] = tuple_result();
                  const {x, label} = record_result();
                  const {sx, slabel} = struct_result();
                  stdio.println(tuple_number);
                  stdio.println(tuple_label);
                  stdio.println(x);
                  stdio.println(label);
                  stdio.println(sx);
                  stdio.println(slabel);
                  return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void returnedRecordAndStructCannotEraseIntoEachOther() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): struct{x: int} {
                          return record{x: 1};
                        }
                        """)));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): record{x: int} {
                          return struct{x: int}{x: 1};
                        }
                        """)));
    }

    @Test
    void returnedProductDestructuringExecutesWithoutChangingFieldOrSlotIdentity()
            throws Exception {
        String program = """
                fnc tuple_result(): [int, string] {
                  return (7, "tuple");
                }

                fnc record_result(): record{x: int, label: string} {
                  return record{label: "record", x: 8};
                }

                fnc struct_result(): struct{x: int, label: string} {
                  return struct{x: int, label: string}{label: "struct", x: 9};
                }

                pub fnc main(): void {
                  const [tuple_number, tuple_label] = tuple_result();
                  const {x, label} = record_result();
                  const {sx, slabel} = struct_result();
                  stdio.println(tuple_number);
                  stdio.println(tuple_label);
                  stdio.println(x);
                  stdio.println(label);
                  stdio.println(sx);
                  stdio.println(slabel);
                  return;
                }
                """;

        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, program, "product-value-boundary.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertTrue(
                text.contains("7\ntuple\n8\nrecord\n9\nstruct"),
                text);
    }
}
