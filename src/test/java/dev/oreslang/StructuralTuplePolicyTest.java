package dev.oreslang;

import dev.oreslang.types.StructuralTuplePolicy;
import dev.oreslang.types.StructuralTuplePolicy.Candidate;
import dev.oreslang.types.StructuralTuplePolicy.Shape;
import dev.oreslang.types.StructuralTuplePolicy.Slot;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Tuple;
import dev.oreslang.types.Types.TupleKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Standalone type/overload proof. NOT executable Oreslang guest evidence. */
final class StructuralTuplePolicyTest {
    private static final Shape EXACT = shape(false);
    private static final Shape STRUCTURAL = shape(true);

    private static Shape shape(boolean structural) {
        return new Shape(List.of(
                new Slot(Primitive.INT, false),
                new Slot(Primitive.INT, false),
                new Slot(Primitive.STRING, true)), structural);
    }

    private static Tuple tuple(dev.oreslang.types.Types.Type... values) {
        return new Tuple(List.of(values));
    }

    @Test
    void exactTupleAllowsOnlyTwoOrThreeSlots() {
        assertTrue(StructuralTuplePolicy.admits(EXACT, tuple(Primitive.INT, Primitive.INT)));
        assertTrue(StructuralTuplePolicy.admits(EXACT, tuple(Primitive.INT, Primitive.INT, Primitive.STRING)));
        assertFalse(StructuralTuplePolicy.admits(EXACT, tuple(Primitive.INT)));
        assertFalse(StructuralTuplePolicy.admits(EXACT, tuple(Primitive.INT, Primitive.INT, Primitive.STRING, Primitive.STRING)));
    }

    @Test
    void structuralTupleAllowsLongerWidthWithoutCoercion() {
        assertTrue(StructuralTuplePolicy.admits(STRUCTURAL, tuple(Primitive.INT, Primitive.INT)));
        assertTrue(StructuralTuplePolicy.admits(STRUCTURAL, tuple(Primitive.INT, Primitive.INT, Primitive.STRING)));
        assertTrue(StructuralTuplePolicy.admits(STRUCTURAL, tuple(Primitive.INT, Primitive.INT, Primitive.STRING, Primitive.STRING)));
        assertFalse(StructuralTuplePolicy.admits(STRUCTURAL, tuple(Primitive.INT, Primitive.STRING)));
        assertFalse(StructuralTuplePolicy.admits(STRUCTURAL, tuple(Primitive.INT, Primitive.INT, Primitive.INT)));
        assertFalse(StructuralTuplePolicy.admits(STRUCTURAL, tuple(Primitive.INT)));
        assertTrue(StructuralTuplePolicy.admits(STRUCTURAL,
                tuple(Primitive.INT, Primitive.INT, new Named("Option", List.of(Primitive.STRING)))));
    }

    @Test
    void prohibitOptionalSlotsBeforeRequiredElements() {
        assertThrows(IllegalArgumentException.class, () -> new Shape(List.of(
                new Slot(Primitive.INT, true), new Slot(Primitive.STRING, false)), true));
    }

    @Test
    void overloadSelectionGivesExpandedArityPrecedence() {
        var family = List.of(new Candidate("two", 2, null), new Candidate("tuple", 1, STRUCTURAL));
        assertEquals(new StructuralTuplePolicy.Selection("two", false),
                StructuralTuplePolicy.selectExplicitTupleSpread(family, tuple(Primitive.INT, Primitive.INT)));
        assertEquals(new StructuralTuplePolicy.Selection("tuple", true),
                StructuralTuplePolicy.selectExplicitTupleSpread(family, tuple(Primitive.INT, Primitive.INT, Primitive.STRING)));
        assertEquals(new StructuralTuplePolicy.Selection("tuple", true),
                StructuralTuplePolicy.selectExplicitTupleSpread(family, tuple(Primitive.INT, Primitive.INT, Primitive.STRING, Primitive.STRING)));
    }

    @Test
    void noFallbackAfterDirectAritySelectionEvenIfTypesMismatch() {
        var family = List.of(new Candidate("direct", 2, null), new Candidate("tuple", 1, STRUCTURAL));
        assertEquals(new StructuralTuplePolicy.Selection("direct", false),
                StructuralTuplePolicy.selectExplicitTupleSpread(family, tuple(Primitive.STRING, Primitive.STRING)));
    }

    @Test
    void unresolvedMutableOrNominalSlotsCannotForgeClosedProofs() {
        assertThrows(IllegalArgumentException.class, () ->
                new Slot(dev.oreslang.types.Types.Unknown.INSTANCE, false));
        assertThrows(IllegalArgumentException.class, () ->
                new Slot(new dev.oreslang.types.Types.Generic("T"), false));
        assertThrows(IllegalArgumentException.class, () ->
                new Slot(new dev.oreslang.types.Types.Borrow(Primitive.INT, true), false));
        assertThrows(IllegalArgumentException.class, () ->
                new Slot(new Named("ActorRef", List.of(Primitive.INT)), false));
        assertThrows(IllegalArgumentException.class, () ->
                new Slot(Primitive.VOID, false));
        assertFalse(StructuralTuplePolicy.admits(STRUCTURAL,
                tuple(Primitive.INT, Primitive.INT,
                        new dev.oreslang.types.Types.Borrow(Primitive.STRING, true))));
    }

    @Test
    void overloadFamilyMustHaveDistinctCallableIdentityAndFiniteBounds() {
        assertThrows(IllegalArgumentException.class, () ->
                StructuralTuplePolicy.selectExplicitTupleSpread(List.of(
                        new Candidate("same", 0, null),
                        new Candidate("same", 1, STRUCTURAL)),
                        tuple(Primitive.INT, Primitive.INT)));
        assertThrows(IllegalArgumentException.class, () ->
                new Candidate("out-of-range", 65, null));

        var excessive = new java.util.ArrayList<Candidate>();
        for (int i = 0; i < 129; i++) {
            excessive.add(new Candidate("f" + i, i % 65, null));
        }
        assertThrows(IllegalArgumentException.class, () ->
                StructuralTuplePolicy.selectExplicitTupleSpread(excessive,
                        tuple(Primitive.INT, Primitive.INT)));

        var tooWide = new java.util.ArrayList<Slot>();
        for (int i = 0; i < 65; i++) tooWide.add(new Slot(Primitive.INT, false));
        assertThrows(IllegalArgumentException.class, () -> new Shape(tooWide, true));
    }

    @Test
    void cannotUseShapeForSameArityOverloadingOrDynamicLists() {
        assertThrows(IllegalArgumentException.class, () ->
                StructuralTuplePolicy.selectExplicitTupleSpread(
                        List.of(new Candidate("a", 1, EXACT), new Candidate("b", 1, STRUCTURAL)),
                        tuple(Primitive.INT, Primitive.INT)));
        assertThrows(IllegalArgumentException.class, () ->
                StructuralTuplePolicy.selectExplicitTupleSpread(
                        List.of(new Candidate("tuple", 1, STRUCTURAL)),
                        new Tuple(List.of(Primitive.INT, Primitive.INT), TupleKind.FIXED_ARRAY)));
    }
}
