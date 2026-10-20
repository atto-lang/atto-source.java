package dev.oreslang;

import dev.oreslang.types.Types;
import dev.oreslang.types.Types.Record;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Rebased-to-main writable structural variance regression for #853. */
final class RecordWriteVarianceCurrentMainTest {
    @Test void writableFieldTypeMustMatchExactly() {
        Types.Type[] candidates = {
                Types.Primitive.INT, Types.Primitive.FLOAT,
                Types.Primitive.DECIMAL, Types.Primitive.BOOL,
                Types.Unknown.INSTANCE,
                new Types.Generic("T"),
                new Types.Named("Box", List.of(Types.Primitive.INT))
        };
        for (Types.Type from : candidates) {
            for (Types.Type to : candidates) {
                var source = new Record(Map.of("field",from));
                var target = new Record(Map.of("field",to));
                assertEquals(from.equals(to), Types.isAssignable(source,target),
                        () -> "writable covariance attempted: "+from+" -> "+to);
            }
        }
    }

    @Test void readonlyWidthAndSafeNumericCovarianceStillWork() {
        var source = new Record(Map.of(
                "field",Types.Primitive.INT,"extra",Types.Primitive.STRING));
        var narrow = new Record(Map.of("field",Types.Primitive.FLOAT),true);
        assertTrue(Types.isAssignable(source,narrow));
        assertFalse(Types.isAssignable(source,
                new Record(Map.of("field",Types.Primitive.FLOAT))));
    }

    @Test void readonlySourceCannotRecoverWriterThroughRecord() {
        var frozen = new Record(Map.of("field",Types.Primitive.INT),true);
        var writable = new Record(Map.of("field",Types.Primitive.INT));
        assertFalse(Types.isAssignable(frozen,writable));
        assertTrue(Types.isAssignable(writable,frozen));
    }

    @Test void readonlyTargetMustRejectUnknownGenericAndNestedUncertainty() {
        var readonly = new Record(Map.of("field",Types.Primitive.INT),true);
        assertFalse(Types.isAssignable(new Record(Map.of(
                "field",Types.Unknown.INSTANCE)),readonly));
        assertFalse(Types.isAssignable(new Record(Map.of(
                "field",new Types.Generic("T"))),readonly));
        assertFalse(Types.isAssignable(new Record(Map.of(
                "field",new Types.Named("Box",List.of(Types.Unknown.INSTANCE)))),
                new Record(Map.of("field",new Types.Named("Box",List.of(
                        Types.Primitive.INT))),true)));
    }

    @Test void nestedWritableAliasesDoNotWidenNestedFields() {
        var original = new Record(Map.of("child",
                new Record(Map.of("value",Types.Primitive.INT))));
        var widened = new Record(Map.of("child",
                new Record(Map.of("value",Types.Primitive.FLOAT))));
        assertFalse(Types.isAssignable(original,widened));
    }

    @Test void structShapeRemainsClosed() {
        var struct = Record.struct(Map.of(
                "field",Types.Primitive.INT,"extra",Types.Primitive.BOOL));
        var fewer = Record.struct(Map.of("field",Types.Primitive.INT));
        assertFalse(Types.isAssignable(struct,fewer));
        assertTrue(Types.isAssignable(struct,struct));
    }
}
