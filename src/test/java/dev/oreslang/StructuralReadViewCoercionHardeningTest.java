package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A read-only structural shape is a field projection, not an implicit
 * numeric conversion or a proof of an unresolved field representation.
 */
final class StructuralReadViewCoercionHardeningTest {
    @Test
    void readonlyRecordIsNotNumericWidening() {
        Types.Record actual = new Types.Record(Map.of("foo", Types.Primitive.INT), true);
        Types.Record expectsFloat = new Types.Record(Map.of("foo", Types.Primitive.FLOAT), true);
        assertTrue(Types.isAssignable(Types.Primitive.INT, Types.Primitive.FLOAT),
                "scalar value promotion remains legal");
        assertFalse(Types.isAssignable(actual, expectsFloat),
                "field-view projection must not promote raw storage");
        assertFalse(Types.isAssignable(
                new Types.Record(Map.of("inner", actual), true),
                new Types.Record(Map.of("inner", expectsFloat), true)),
                "nested field-view projection cannot hide a coercion");
        assertFalse(Types.isAssignable(
                new Types.Record(Map.of("foo", new Types.Tuple(
                        java.util.List.of(Types.Primitive.INT))), true),
                new Types.Record(Map.of("foo", new Types.Tuple(
                        java.util.List.of(Types.Primitive.FLOAT))), true)),
                "tuple elements are not magically converted on projection");
    }

    @Test
    void unknownCanBeReadAsOpaqueButCannotProveConcreteFieldType() {
        Types.Record unresolved = new Types.Record(Map.of("foo", Types.Unknown.INSTANCE), true);
        Types.Record known = new Types.Record(Map.of("foo", Types.Primitive.INT), true);
        assertFalse(Types.isAssignable(unresolved, known));
        assertTrue(Types.isAssignable(known, unresolved),
                "opaque Unknown destination cannot be mistaken for a concrete proof");
        assertFalse(Types.isAssignable(
                new Types.Record(Map.of("foo", new Types.Generic("T")), true), known));
    }

    @Test
    void readonlyCovarianceStillAllowsSafeWidthAndUnionViews() {
        Types.Record actual = new Types.Record(
                Map.of("foo", Types.Primitive.INT, "bar", Types.Primitive.STRING), true);
        Types.Record required = new Types.Record(Map.of("foo",
                Types.unionOf(Types.Primitive.INT, Types.Primitive.STRING)), true);
        assertTrue(Types.isAssignable(actual, required));
    }

    @Test
    void sourceRejectsUnconvertedScalarAndNestedFields() {
        for (String shape : java.util.List.of(
                "record{foo: float}", "struct{foo: float}", "structural {foo: float}")) {
            String source = """
                    fnc read(SHAPE v): void { return; }
                    fnc bad(): void {
                        read(infer struct{foo: 3, extra: "kept"});
                        return;
                    }
                    """.replace("SHAPE", shape);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), shape);
        }

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc read(record{inner: record{foo: float}} v): void { return; }
                fnc bad(): void {
                    read(infer struct{inner: infer struct{foo: 3, extra: true}});
                    return;
                }
                """)));
    }

    @Test
    void sourcePreservesSafeReadonlyWidthViews() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc read(record{foo: int | string} v): void { return; }
                fnc ok(): void {
                    const wider = infer struct{foo: 3, extra: "kept"};
                    read(wider);
                    return;
                }
                """)));
    }
}
