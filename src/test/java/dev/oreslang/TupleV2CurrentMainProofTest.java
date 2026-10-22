package dev.oreslang;

import dev.oreslang.types.FixedProductCallPlan;
import dev.oreslang.types.StructuralTuplePolicy;
import dev.oreslang.types.Types;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class TupleV2CurrentMainProofTest {
    private static StructuralTuplePolicy.Shape shape(boolean structural) {
        return new StructuralTuplePolicy.Shape(List.of(
                new StructuralTuplePolicy.Slot(Types.Primitive.INT, false),
                new StructuralTuplePolicy.Slot(Types.Primitive.INT, false),
                new StructuralTuplePolicy.Slot(Types.Primitive.STRING, true)),
                structural);
    }

    @Test
    void tupleShapeCannotConfuseGroupingOrUnitWithProduct() {
        assertThrows(IllegalArgumentException.class,
                () -> new StructuralTuplePolicy.Shape(List.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> new StructuralTuplePolicy.Shape(List.of(
                        new StructuralTuplePolicy.Slot(Types.Primitive.INT, false)), false));
    }

    @Test
    void optionalSlotsAreTrailingOnlyAndNeverImplicitNull() {
        assertThrows(IllegalArgumentException.class, () ->
                new StructuralTuplePolicy.Shape(List.of(
                        new StructuralTuplePolicy.Slot(Types.Primitive.INT, true),
                        new StructuralTuplePolicy.Slot(Types.Primitive.STRING, false)), false));

        var exact = shape(false);
        assertTrue(StructuralTuplePolicy.admits(exact,
                new Types.Tuple(List.of(Types.Primitive.INT, Types.Primitive.INT))));
        assertFalse(StructuralTuplePolicy.admits(exact,
                new Types.Tuple(List.of(Types.Primitive.INT))));
        assertFalse(StructuralTuplePolicy.admits(exact,
                new Types.Tuple(List.of(
                        Types.Primitive.INT, Types.Primitive.INT, Types.Primitive.NULL))));
    }

    @Test
    void structuralHiddenTailCannotLaunderAuthority() {
        var structural = shape(true);
        for (Types.Type unsafe : List.of(
                Types.Unknown.INSTANCE,
                new Types.Generic("T"),
                new Types.Borrow(Types.Primitive.INT, true),
                new Types.Named("ActorRef", List.of(Types.Primitive.INT)),
                new Types.ListType(Types.Primitive.INT),
                new Types.Function(List.of(Types.Primitive.INT), Types.Primitive.INT))) {
            var actual = new Types.Tuple(List.of(
                    Types.Primitive.INT, Types.Primitive.INT,
                    Types.Primitive.STRING, unsafe));
            assertFalse(StructuralTuplePolicy.admits(structural, actual), unsafe.toString());
        }
        assertTrue(StructuralTuplePolicy.admits(structural,
                new Types.Tuple(List.of(
                        Types.Primitive.INT, Types.Primitive.INT,
                        Types.Primitive.STRING, Types.Primitive.BOOL))));
    }

    @Test
    void explicitSpreadHasDeterministicDirectArityPrecedence() {
        var family = List.of(
                new StructuralTuplePolicy.Candidate("expanded", 3, null),
                new StructuralTuplePolicy.Candidate("pack", 1, shape(true)));
        var tuple = new Types.Tuple(List.of(
                Types.Primitive.INT, Types.Primitive.INT, Types.Primitive.STRING));
        assertEquals(new StructuralTuplePolicy.Selection("expanded", false),
                StructuralTuplePolicy.selectExplicitTupleSpread(family, tuple));

        // Selection never retries the pack after ordinary arity selection.
        var mismatched = new Types.Tuple(List.of(
                Types.Primitive.STRING, Types.Primitive.STRING, Types.Primitive.STRING));
        assertEquals(new StructuralTuplePolicy.Selection("expanded", false),
                StructuralTuplePolicy.selectExplicitTupleSpread(family, mismatched));
    }

    @Test
    void packFallbackRequiresExactlyOneStaticCandidate() {
        var tuple = new Types.Tuple(List.of(
                Types.Primitive.INT, Types.Primitive.INT, Types.Primitive.STRING,
                Types.Primitive.BOOL));
        assertEquals(new StructuralTuplePolicy.Selection("pack", true),
                StructuralTuplePolicy.selectExplicitTupleSpread(
                        List.of(new StructuralTuplePolicy.Candidate("pack", 1, shape(true))),
                        tuple));
        assertThrows(IllegalArgumentException.class, () ->
                StructuralTuplePolicy.selectExplicitTupleSpread(List.of(
                        new StructuralTuplePolicy.Candidate("a", 1, shape(true)),
                        new StructuralTuplePolicy.Candidate("b", 1, shape(true))), tuple));
    }

    @Test
    void fixedProductBinderPreservesLexicalEvaluationOrder() {
        var names = List.of("a", "b", "c");
        var plan = FixedProductCallPlan.bind(names, List.of(
                FixedProductCallPlan.InputSlot.positional(),
                FixedProductCallPlan.InputSlot.named("c"),
                FixedProductCallPlan.InputSlot.named("b")));
        assertEquals(List.of(0, 2, 1), plan.abiToSource());
        assertEquals(List.of(0, 1, 2),
                plan.sourceOrder().stream()
                        .map(FixedProductCallPlan.BoundSlot::sourceIndex).toList());
    }

    @Test
    void deterministicFuzzNeverProducesNonPermutationBinder() {
        var random = new Random(0x7A11E2L);
        for (int arity = 0; arity <= 32; arity++) {
            var names = new ArrayList<String>();
            for (int i = 0; i < arity; i++) names.add("p" + i);
            for (int round = 0; round < 250; round++) {
                int prefix = random.nextInt(arity + 1);
                var tail = new ArrayList<Integer>();
                for (int i = prefix; i < arity; i++) tail.add(i);
                java.util.Collections.shuffle(tail, random);
                var inputs = new ArrayList<FixedProductCallPlan.InputSlot>();
                for (int i = 0; i < prefix; i++)
                    inputs.add(FixedProductCallPlan.InputSlot.positional());
                for (int target : tail)
                    inputs.add(FixedProductCallPlan.InputSlot.named(names.get(target)));
                var bound = FixedProductCallPlan.bind(names, inputs);
                assertEquals(arity, bound.abiToSource().size());
                assertEquals(arity, new java.util.HashSet<>(bound.abiToSource()).size());
                assertEquals(bound, FixedProductCallPlan.bind(names, inputs));
            }
        }
    }
}
