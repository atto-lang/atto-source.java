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
    void checkedProjectionIsTheOnlySourceOfSlotProvenance() {
        var absent = StructuralTuplePolicy.projection(
                shape(false), tuple(Types.Primitive.INT, Types.Primitive.STRING)).orElseThrow();
        assertEquals(List.of(0, 1, -1),
                absent.visibleSlots().stream()
                        .map(StructuralTuplePolicy.ProjectedSlot::sourceIndex).toList());
        assertEquals(List.of(
                Materialization.KEEP_VALUE,
                Materialization.KEEP_VALUE,
                Materialization.SYNTHESIZE_NONE),
                absent.visibleSlots().stream()
                        .map(StructuralTuplePolicy.ProjectedSlot::materialization).toList());
        for (int index = 0; index < absent.visibleWidth(); index++) {
            assertEquals(index, absent.visibleSlot(index).visibleIndex());
        }
        assertTrue(StructuralTuplePolicy.projection(shape(false), tuple(
                Types.Primitive.INT,
                new Types.Named("Option", List.of(Types.Primitive.STRING)))).isEmpty());
    }
    @Test
    void structuralLifetimeTagSurvivesAnEqualWidthProjection() {
        var input = tuple(Types.Primitive.INT, Types.Primitive.STRING,
                Types.Primitive.BOOL);
        var structural = StructuralTuplePolicy.projection(shape(true), input).orElseThrow();
        var exact = StructuralTuplePolicy.projection(shape(false), input).orElseThrow();
        assertEquals(0, structural.hiddenTailWidth());
        assertEquals(0, exact.hiddenTailWidth());
        assertEquals(StructuralTuplePolicy.ProjectionKind.READONLY_NONESCAPING_VIEW,
                structural.kind());
        assertTrue(structural.requiresNonescapingOwnershipCheck());
        assertEquals(StructuralTuplePolicy.ProjectionKind.EXACT_TUPLE_VALUE,
                exact.kind());
        assertFalse(exact.requiresNonescapingOwnershipCheck());

        // Optional omission does not turn a structural borrow/view into
        // a freely escaping exact value.
        var omitted = StructuralTuplePolicy.projection(shape(true),
                tuple(Types.Primitive.INT, Types.Primitive.STRING)).orElseThrow();
        assertTrue(omitted.requiresNonescapingOwnershipCheck());
    }

    @Test
    void recursiveDagProofIsBoundedByTotalNodesNotOnlyDepth() {
        Types.Type small = Types.Primitive.INT;
        for (int i = 0; i < 8; i++) {
            small = new Types.Tuple(List.of(small, small));
        }
        final Types.Type moderate = small;
        assertDoesNotThrow(() -> new Slot(moderate, false));

        // This is only 26 allocated record nodes, but naively recursively
        // visiting both repeated children would take over 67 million visits.
        Types.Type expanded = Types.Primitive.INT;
        for (int i = 0; i < 26; i++) {
            expanded = new Types.Tuple(List.of(expanded, expanded));
        }
        final Types.Type adversarial = expanded;
        assertThrows(IllegalArgumentException.class, () -> new Slot(adversarial, false));
    }

    @Test
    void tupleArityLimitsApplyToSourceAndDeclaredShapes() {
        List<Types.Type> sixtyFour = java.util.Collections.nCopies(64, Types.Primitive.INT);
        List<Types.Type> sixtyFive = java.util.Collections.nCopies(65, Types.Primitive.INT);
        var allRequired = new Shape(
                sixtyFour.stream().map(t -> new Slot(t, false)).toList(), false);
        assertEquals(64, StructuralTuplePolicy.projection(
                allRequired, new Types.Tuple(sixtyFour)).orElseThrow().visibleWidth());
        assertTrue(StructuralTuplePolicy.projection(
                allRequired, new Types.Tuple(sixtyFive)).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new Shape(sixtyFive.stream()
                        .map(t -> new Slot(t, false)).toList(), false));
    }

    @Test
    void staticPackResolutionRetainsItsOriginalProjectionProof() {
        var input = tuple(Types.Primitive.INT, Types.Primitive.STRING,
                Types.Primitive.BOOL, Types.Primitive.DECIMAL);
        var pack = new StructuralTuplePolicy.Candidate("pack", 1, shape(true));
        var resolved = StructuralTuplePolicy.resolveExplicitTupleSpread(List.of(pack), input);
        assertEquals(new StructuralTuplePolicy.Selection("pack", true),
                resolved.selection());
        var plan = resolved.packingProjection().orElseThrow();
        assertEquals(1, plan.hiddenTailWidth());
        assertEquals(3, plan.visibleWidth());
        assertTrue(plan.requiresNonescapingOwnershipCheck());
        assertEquals(Materialization.WRAP_SOME, plan.visibleSlot(2).materialization());

        // Expanded arity wins deterministically; it has no implicit tuple
        // packing projection and cannot fall back after a type mismatch.
        var expanded = new StructuralTuplePolicy.Candidate("expanded", 4, null);
        var direct = StructuralTuplePolicy.resolveExplicitTupleSpread(
                List.of(pack, expanded), input);
        assertEquals(new StructuralTuplePolicy.Selection("expanded", false),
                direct.selection());
        assertTrue(direct.packingProjection().isEmpty());

        var omitted = StructuralTuplePolicy.resolveExplicitTupleSpread(
                List.of(pack), tuple(Types.Primitive.INT, Types.Primitive.STRING));
        assertEquals(Materialization.SYNTHESIZE_NONE,
                omitted.packingProjection().orElseThrow()
                        .visibleSlot(2).materialization());
    }

    @Test
    void fixedProductBinderHasTheSameSixtyFourSlotResourceLimit() {
        List<String> maxNames = java.util.stream.IntStream.range(0, 64)
                .mapToObj(i -> "p" + i).toList();
        var maxArgs = java.util.Collections.nCopies(
                64, dev.oreslang.types.FixedProductCallPlan.InputSlot.positional());
        var plan = dev.oreslang.types.FixedProductCallPlan.bind(maxNames, maxArgs);
        assertEquals(64, plan.abiToSource().size());

        List<String> tooManyNames = java.util.stream.IntStream.range(0, 65)
                .mapToObj(i -> "p" + i).toList();
        var tooManyArgs = java.util.Collections.nCopies(
                65, dev.oreslang.types.FixedProductCallPlan.InputSlot.positional());
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.FixedProductCallPlan.bind(tooManyNames, tooManyArgs));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.FixedProductCallPlan.bind(tooManyNames, maxArgs));
    }

}
