package dev.oreslang;

import dev.oreslang.types.StructuralContainerPolicy;
import dev.oreslang.types.StructuralContainerPolicy.Kind;
import dev.oreslang.types.StructuralContainerPolicy.Shape;
import dev.oreslang.types.Types;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.oreslang.types.StructuralContainerPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

/** Static proof only. Does not certify parser, code emission or Oreslang guest execution. */
final class StructuralContainerPolicyTest {

    @Test void matchingArrayListAndMapReadonlyViewsAreAdmitted() {
        assertTrue(admits(array(Primitive.INT, false), array(Primitive.INT, true)));
        assertTrue(admits(list(Primitive.STRING, false), list(Primitive.STRING, true)));
        assertTrue(admits(map(Primitive.STRING, Primitive.INT, false),
                          map(Primitive.STRING, Primitive.INT, true)));
    }

    @Test void directWritableCollectionsRemainInvariant() {
        var narrow = new Types.StringLiteral("literal");
        assertFalse(admits(list(narrow, false), list(Primitive.STRING, false)));
        assertTrue(admits(list(Primitive.STRING, false), list(Primitive.STRING, false)));
        assertFalse(admits(array(Primitive.INT, true), array(Primitive.INT, false)));
        assertFalse(admits(map(Primitive.STRING, Primitive.STRING, true),
                           map(Primitive.STRING, Primitive.STRING, false)));
    }

    @Test void readonlyClosedLiteralValuesCanBeObservedAsPrimitives() {
        assertTrue(admits(array(new Types.StringLiteral("ok"), false),
                          array(Primitive.STRING, true)));
        assertTrue(admits(list(new Types.BoolLiteral(true), false),
                          list(Primitive.BOOL, true)));
        assertTrue(admits(map(Primitive.STRING, new Types.StringLiteral("ok"), false),
                          map(Primitive.STRING, Primitive.STRING, true)));
    }

    @Test void numericWideningIsNotRepresentationSafeContainerCovariance() {
        assertFalse(admits(array(Primitive.INT, false), array(Primitive.FLOAT, true)));
        assertFalse(admits(list(Primitive.FLOAT, false), list(Primitive.COMPLEX, true)));
        assertFalse(admits(map(Primitive.STRING, Primitive.INT, false),
                           map(Primitive.STRING, Primitive.DECIMAL, true)));
    }

    @Test void neverConflateArraysListsAndTuples() {
        assertFalse(admits(array(Primitive.INT, false), list(Primitive.INT, true)));
        assertFalse(admits(fixedArray(Primitive.INT, 2, false),
                           fixedList(Primitive.INT, 2, true)));
        assertFalse(admits(fixedArray(Primitive.INT, 2, false),
                           array(Primitive.INT, true)));
        assertFalse(admits(list(Primitive.INT, false),
                           map(Primitive.STRING, Primitive.INT, true)));
    }

    @Test void fixedSequenceTypeCannotLoseOrContradictHomogeneousMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> new Types.Tuple(List.of(), Types.TupleKind.FIXED_ARRAY));
        assertThrows(IllegalArgumentException.class,
                () -> new Types.Tuple(List.of(), Types.TupleKind.FIXED_LIST));
        assertThrows(IllegalArgumentException.class,
                () -> new Types.Tuple(
                        List.of(Primitive.INT, Primitive.STRING),
                        Types.TupleKind.FIXED_ARRAY));
        assertThrows(IllegalArgumentException.class,
                () -> new Types.Tuple(
                        List.of(Primitive.INT),
                        Types.TupleKind.FIXED_LIST,
                        Primitive.STRING));
        assertDoesNotThrow(() -> new Types.Tuple(
                List.of(),
                Types.TupleKind.FIXED_ARRAY,
                Primitive.INT));
    }

    @Test void fixedCollectionsRequireExactExtent() {
        assertTrue(admits(fixedArray(Primitive.STRING, 4, false),
                          fixedArray(Primitive.STRING, 4, true)));
        assertFalse(admits(fixedArray(Primitive.STRING, 3, false),
                           fixedArray(Primitive.STRING, 4, true)));
        assertTrue(admits(fixedList(Primitive.INT, 0, false),
                          fixedList(Primitive.INT, 0, true)));
        assertFalse(admits(fixedList(Primitive.INT, 0, false),
                           fixedList(Primitive.INT, 1, true)));
        assertThrows(IllegalArgumentException.class,
                     () -> fixedArray(Primitive.INT, 65, true));
        assertThrows(IllegalArgumentException.class,
                     () -> fixedList(Primitive.INT, -1, true));
    }

    @Test void mapKeysMustStayExactlyInvariant() {
        var key = new Types.StringLiteral("k");
        assertFalse(admits(map(key, Primitive.INT, false),
                           map(Primitive.STRING, Primitive.INT, true)));
        assertFalse(admits(map(Primitive.INT, Primitive.INT, false),
                           map(Primitive.STRING, Primitive.INT, true)));
        assertTrue(admits(map(Primitive.BOOL, Primitive.STRING, false),
                          map(Primitive.BOOL, Primitive.STRING, true)));
    }

    @Test void unstableUnknownAndBorrowedMapKeysCannotForgeHashIdentity() {
        assertThrows(IllegalArgumentException.class,
                     () -> map(Primitive.FLOAT, Primitive.INT, true));
        assertThrows(IllegalArgumentException.class,
                     () -> map(Primitive.COMPLEX, Primitive.INT, true));
        assertThrows(IllegalArgumentException.class,
                     () -> map(Types.Unknown.INSTANCE, Primitive.INT, true));
        assertThrows(IllegalArgumentException.class,
                     () -> map(new Types.Generic("K"), Primitive.INT, true));
        assertThrows(IllegalArgumentException.class,
                     () -> map(new Types.Borrow(Primitive.STRING, false), Primitive.INT, true));
    }

    @Test void unresolvedTypesAndMutableAliasesAreNotReadonlySafe() {
        assertThrows(IllegalArgumentException.class,
                     () -> list(Types.Unknown.INSTANCE, true));
        assertThrows(IllegalArgumentException.class,
                     () -> array(new Types.Generic("T"), true));
        assertThrows(IllegalArgumentException.class,
                     () -> list(new Types.Borrow(Primitive.INT, true), true));
        assertThrows(IllegalArgumentException.class,
                     () -> list(new Types.ListType(Primitive.INT), true));
        assertThrows(IllegalArgumentException.class,
                     () -> map(Primitive.STRING, new Types.Named("Map",
                              List.of(Primitive.STRING, Primitive.INT)), true));
        assertThrows(IllegalArgumentException.class,
                     () -> list(new Types.Function(List.of(), Primitive.INT), true));
    }

    @Test void readonlyRecordMembersPermitOnlyClosedCovariantWidth() {
        var source = new Record(Map.of(
                "label", new Types.StringLiteral("ok"),
                "extra", Primitive.INT), true);
        var target = new Record(Map.of("label", Primitive.STRING), true);
        assertTrue(admits(list(source, false), list(target, true)));
        assertFalse(admits(list(source, false), list(target, false)));
    }

    @Test void mutableOrUnresolvedHiddenFieldsMustNotBeLaundered() {
        var target = new Record(Map.of("label", Primitive.STRING), true);
        var unsafe = new Record(Map.of("label", Primitive.STRING,
                                      "extra", new Types.ListType(Primitive.INT)), true);
        assertThrows(IllegalArgumentException.class, () -> list(unsafe, true));
        var mutable = new Record(Map.of("label", Primitive.STRING), false);
        assertThrows(IllegalArgumentException.class, () -> list(mutable, true));
        var unknown = new Record(Map.of("label", Primitive.STRING,
                                       "extra", Types.Unknown.INSTANCE), true);
        assertThrows(IllegalArgumentException.class, () -> array(unknown, true));
        assertTrue(admits(array(target, false), array(target, true)));
    }

    @Test void safeOptionalNestedValuesStayClosed() {
        var source = new Types.Named("Option", List.of(new Types.StringLiteral("a")));
        var target = new Types.Named("Option", List.of(Primitive.STRING));
        assertTrue(admits(list(source, false), list(target, true)));
        assertFalse(admits(list(source, false), list(target, false)));
    }

    @Test void positionalNestedTupleMustNotStandInForFixedArray() {
        var inner = new Types.Tuple(List.of(Primitive.INT, Primitive.INT));
        var fixed = new Types.Tuple(List.of(Primitive.INT, Primitive.INT),
                                    Types.TupleKind.FIXED_ARRAY);
        assertTrue(admits(array(inner, false), array(inner, true)));
        assertThrows(IllegalArgumentException.class, () -> array(fixed, true));
    }

    @Test void shapeMetadataCannotMixMapKeysOrDynamicLengths() {
        assertThrows(IllegalArgumentException.class,
                     () -> new Shape(Kind.ARRAY, Primitive.STRING, Primitive.INT, -1, true));
        assertThrows(IllegalArgumentException.class,
                     () -> new Shape(Kind.MAP, Primitive.STRING, Primitive.INT, 2, true));
        assertThrows(IllegalArgumentException.class,
                     () -> new Shape(Kind.LIST, null, Primitive.INT, 2, true));
        assertThrows(NullPointerException.class,
                     () -> new Shape(null, null, Primitive.INT, -1, true));
        assertThrows(NullPointerException.class,
                     () -> new Shape(Kind.MAP, Primitive.STRING, null, -1, true));
    }

    @Test void nestedOptionAndRecordProjectionCannotWidenNumbers() {
        var from = new Record(Map.of("value",
                new Types.Named("Option", List.of(Primitive.INT))), true);
        var to = new Record(Map.of("value",
                new Types.Named("Option", List.of(Primitive.FLOAT))), true);
        assertFalse(admits(array(from, true), array(to, true)));
    }
}
