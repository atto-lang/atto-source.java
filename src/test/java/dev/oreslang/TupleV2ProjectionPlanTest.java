package dev.oreslang;

import dev.oreslang.types.StructuralTuplePolicy;
import dev.oreslang.types.StructuralTuplePolicy.Materialization;
import dev.oreslang.types.StructuralTuplePolicy.Shape;
import dev.oreslang.types.StructuralTuplePolicy.Slot;
import dev.oreslang.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TupleV2ProjectionPlanTest {
    private static Shape shape(boolean structural) {
        return new Shape(List.of(
                new Slot(Types.Primitive.INT, false),
                new Slot(Types.Primitive.STRING, false),
                new Slot(Types.Primitive.BOOL, true)), structural);
    }

    private static Types.Tuple tuple(Types.Type... elements) {
        return new Types.Tuple(List.of(elements));
    }

    @Test
    void omittedTailAndSuppliedValueHaveDistinctMaterializationModes() {
        var absent = StructuralTuplePolicy.projection(
                shape(false), tuple(Types.Primitive.INT, Types.Primitive.STRING)).orElseThrow();
        assertEquals(2, absent.sourceArity());
        assertEquals(3, absent.visibleWidth());
        assertEquals(0, absent.hiddenTailWidth());
        assertEquals(Materialization.KEEP_VALUE, absent.visibleSlot(0).materialization());
        assertEquals(Materialization.SYNTHESIZE_NONE, absent.visibleSlot(2).materialization());
        assertEquals(-1, absent.visibleSlot(2).sourceIndex());
        assertEquals(new Types.Named("Option", List.of(Types.Primitive.BOOL)),
                absent.visibleSlot(2).outputType());

        var value = StructuralTuplePolicy.projection(
                shape(false), tuple(Types.Primitive.INT, Types.Primitive.STRING,
                        Types.Primitive.BOOL)).orElseThrow();
        assertEquals(Materialization.WRAP_SOME, value.visibleSlot(2).materialization());
        assertEquals(2, value.visibleSlot(2).sourceIndex());

        var option = StructuralTuplePolicy.projection(
                shape(false), tuple(Types.Primitive.INT, Types.Primitive.STRING,
                        new Types.Named("Option", List.of(Types.Primitive.BOOL)))).orElseThrow();
        assertEquals(Materialization.KEEP_OPTION, option.visibleSlot(2).materialization());
        assertEquals(2, option.visibleSlot(2).sourceIndex());

        assertTrue(StructuralTuplePolicy.projection(shape(false),
                tuple(Types.Primitive.INT, Types.Primitive.STRING,
                        Types.Primitive.NULL)).isEmpty());
    }

    @Test
    void structuralWidthOnlyExposesDeclaredIndexesAndDoesNotLaunderHiddenAuthority() {
        var plan = StructuralTuplePolicy.projection(shape(true), tuple(
                Types.Primitive.INT, Types.Primitive.STRING,
                Types.Primitive.BOOL, Types.Primitive.DECIMAL)).orElseThrow();
        assertEquals(4, plan.sourceArity());
        assertEquals(3, plan.visibleWidth());
        assertEquals(1, plan.hiddenTailWidth());
        assertEquals(3, plan.visibleSlots().size());
        assertThrows(IndexOutOfBoundsException.class, () -> plan.visibleSlot(3));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.visibleSlots().clear());
        assertTrue(StructuralTuplePolicy.projection(shape(false), tuple(
                Types.Primitive.INT, Types.Primitive.STRING,
                Types.Primitive.BOOL, Types.Primitive.DECIMAL)).isEmpty());

        for (Types.Type hidden : List.of(
                new Types.Borrow(Types.Primitive.INT, true),
                new Types.Named("ActorRef", List.of(Types.Primitive.INT)),
                new Types.ListType(Types.Primitive.INT),
                new Types.Function(List.of(Types.Primitive.INT), Types.Primitive.INT),
                Types.Unknown.INSTANCE)) {
            assertTrue(StructuralTuplePolicy.projection(shape(true), tuple(
                    Types.Primitive.INT, Types.Primitive.STRING,
                    Types.Primitive.BOOL, hidden)).isEmpty(), hidden.toString());
        }
    }

    @Test
    void zeroAndSingletonSourceAndNestedTuplesFailClosed() {
        var family = List.of(new StructuralTuplePolicy.Candidate("one", 1, null));
        for (Types.Tuple invalid : List.of(
                new Types.Tuple(List.of()),
                tuple(Types.Primitive.INT))) {
            assertTrue(StructuralTuplePolicy.projection(shape(true), invalid).isEmpty());
            assertThrows(IllegalArgumentException.class,
                    () -> StructuralTuplePolicy.selectExplicitTupleSpread(family, invalid));
        }

        assertThrows(IllegalArgumentException.class, () ->
                new Slot(new Types.Tuple(List.of()), false));
        assertThrows(IllegalArgumentException.class, () ->
                new Slot(tuple(Types.Primitive.INT), false));
    }

    @Test
    void projectionSlotsCannotForgeAnAbsenceWithARealSourceIndex() {
        assertThrows(IllegalArgumentException.class, () ->
                new StructuralTuplePolicy.ProjectedSlot(0, 0,
                        Types.Primitive.INT, Materialization.SYNTHESIZE_NONE));
        assertThrows(IllegalArgumentException.class, () ->
                new StructuralTuplePolicy.ProjectedSlot(0, -1,
                        Types.Primitive.INT, Materialization.KEEP_VALUE));
    }
}
