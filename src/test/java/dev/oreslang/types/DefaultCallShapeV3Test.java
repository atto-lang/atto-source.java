package dev.oreslang.types;

import dev.oreslang.types.DefaultCallShapeV3.*;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DefaultCallShapeV3Test {
    private static Formal plain(String name) { return new Formal(name, false, false, null); }
    private static Formal none(String name) { return new Formal(name, true, true, null); }
    private static Formal defaulted(String name, long number) {
        return new Formal(name, false, true, new Constant(ConstantKind.INTEGER, number));
    }

    @Test void namedSourceOrderAndFormalAbiOrderAreIndependent() {
        Plan p = DefaultCallShapeV3.bind(List.of(plain("a"), defaulted("b", 9), plain("c")),
                List.of(Item.value("c"), Item.value("a")));
        assertEquals(List.of(0, 1), p.evaluatedRawIndices());
        assertEquals(1, p.abiSlots().get(0).rawItemIndex());
        assertEquals(1, p.abiSlots().get(0).evaluatedIndex());
        assertEquals(Fill.DEFAULT_VALUE, p.abiSlots().get(1).fill());
        assertEquals(0, p.abiSlots().get(2).rawItemIndex());
    }

    @Test void omissionIsNotAnEvaluatedExpression() {
        Plan p = DefaultCallShapeV3.bind(List.of(plain("a"), none("b"), defaulted("c", 5)),
                List.of(Item.value(null), Item.omit(null), Item.value(null)));
        assertEquals(List.of(0, 2), p.evaluatedRawIndices());
        assertEquals(Origin.EXPLICIT_OMISSION, p.abiSlots().get(1).origin());
        assertEquals(Fill.IMPLICIT_NONE, p.abiSlots().get(1).fill());
        assertEquals(-1, p.abiSlots().get(1).evaluatedIndex());
    }

    @Test void optionDefaultsAreWrappedExactlyOnce() {
        Formal option = new Formal("value", true, true,
                new Constant(ConstantKind.STRING, "charlie"));
        Plan p = DefaultCallShapeV3.bind(List.of(option), List.of());
        assertEquals(Fill.DEFAULT_SOME, p.abiSlots().getFirst().fill());
        Plan explicitNone = DefaultCallShapeV3.bind(
                List.of(new Formal("value", true, true,
                        new Constant(ConstantKind.NONE, null))), List.of());
        assertEquals(Fill.DEFAULT_NONE, explicitNone.abiSlots().getFirst().fill());
    }

    @Test void explicitSelfIsStillAUserSuppliedValue() {
        assertThrows(IllegalArgumentException.class, () -> DefaultCallShapeV3.bind(
                List.of(plain("x")), List.of(Item.value(null), Item.value(null))));
        Plan p = DefaultCallShapeV3.bind(
                List.of(plain("peer"), plain("x")), List.of(Item.value(null), Item.value(null)));
        assertEquals(2, p.evaluatedRawIndices().size());
        assertEquals(List.of(0, 1), p.evaluatedRawIndices());
    }

    @Test void missingOrDuplicateOrUnknownArgumentAlwaysFails() {
        List<Formal> args = List.of(plain("a"), none("b"));
        assertThrows(IllegalArgumentException.class, () -> DefaultCallShapeV3.bind(args, List.of()));
        assertThrows(IllegalArgumentException.class, () -> DefaultCallShapeV3.bind(args, List.of(Item.omit("a"))));
        assertThrows(IllegalArgumentException.class, () -> DefaultCallShapeV3.bind(args,
                List.of(Item.value("a"), Item.value("a"))));
        assertThrows(IllegalArgumentException.class, () -> DefaultCallShapeV3.bind(args,
                List.of(Item.value("unknown"))));
        assertThrows(IllegalArgumentException.class, () -> DefaultCallShapeV3.bind(args,
                List.of(Item.value("a"), Item.value(null))));
    }

    @Test void conservativeCountIntervalsCannotSelectByArgumentTypesOrNames() {
        assertThrows(IllegalArgumentException.class, () -> DefaultCallShapeV3.rejectOverlappingArities(
                List.of(plain("x"), defaulted("y", 3)), List.of(plain("q"))));
        assertDoesNotThrow(() -> DefaultCallShapeV3.rejectOverlappingArities(
                List.of(plain("x")), List.of(plain("x"), plain("y"))));
    }

    @Test void malformedMetadataDoesNotConstructAPlan() {
        assertThrows(IllegalArgumentException.class, () -> new Formal("self", true, true, null));
        assertThrows(IllegalArgumentException.class, () -> new Formal("x", false, true, null));
        assertThrows(IllegalArgumentException.class, () -> new Constant(ConstantKind.INTEGER, "1"));
        assertThrows(IllegalArgumentException.class, () -> new Bound(0, 0, -1,
                Origin.PROVIDED, Fill.EXPRESSION, null));
        assertThrows(IllegalArgumentException.class, () -> new Plan(List.of(), List.of(0)));
        assertThrows(IllegalArgumentException.class, () -> new Bound(0, -1, -1,
                Origin.UNSUPPLIED, Fill.DEFAULT_NONE, new Constant(ConstantKind.INTEGER, 5L)));
        assertThrows(IllegalArgumentException.class, () -> new Plan(List.of(
                new Bound(0, 1, 0, Origin.PROVIDED, Fill.EXPRESSION, null),
                new Bound(1, 1, 1, Origin.PROVIDED, Fill.EXPRESSION, null)), List.of(1, 1)));
    }

    @Test void forgedPlanCannotSkipSourceItemsOrReuseOmissionMarkers() {
        // The sole supplied expression cannot secretly refer to the second
        // source item while silently ignoring the first.
        assertThrows(IllegalArgumentException.class, () -> new Plan(List.of(
                new Bound(0, 1, 0, Origin.PROVIDED, Fill.EXPRESSION, null)),
                List.of(1)));

        // An explicit omission and a provided expression cannot point at the
        // same raw item; both source positions must be uniquely accounted for.
        assertThrows(IllegalArgumentException.class, () -> new Plan(List.of(
                new Bound(0, 0, -1, Origin.EXPLICIT_OMISSION, Fill.IMPLICIT_NONE, null),
                new Bound(1, 0, 0, Origin.PROVIDED, Fill.EXPRESSION, null)),
                List.of(0)));

        // Two omission markers cannot alias the same source token.
        assertThrows(IllegalArgumentException.class, () -> new Plan(List.of(
                new Bound(0, 0, -1, Origin.EXPLICIT_OMISSION, Fill.IMPLICIT_NONE, null),
                new Bound(1, 0, -1, Origin.EXPLICIT_OMISSION, Fill.IMPLICIT_NONE, null)),
                List.of()));

        Plan valid = DefaultCallShapeV3.bind(
                List.of(new Formal("a", true, true, null),
                        new Formal("b", true, true, null),
                        new Formal("c", true, true, null)),
                List.of(Item.omit(null), Item.value(null), Item.omit(null)));
        assertEquals(List.of(1), valid.evaluatedRawIndices());
        assertEquals(Origin.EXPLICIT_OMISSION, valid.abiSlots().get(0).origin());
        assertEquals(Origin.EXPLICIT_OMISSION, valid.abiSlots().get(2).origin());
    }

    @Test void randomizedAcceptedPlansPreserveEveryRawExpressionExactlyOnce() {
        Random rng = new Random(20261019);
        int accepted = 0;
        for (int iteration = 0; iteration < 45000; iteration++) {
            int n = rng.nextInt(9), count = rng.nextInt(11);
            java.util.ArrayList<Formal> formals = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                boolean option = rng.nextBoolean(), marked = rng.nextBoolean();
                Constant fallback = rng.nextInt(5) == 0
                        ? new Constant(ConstantKind.INTEGER, (long) rng.nextInt(20)) : null;
                if (marked && !option && fallback == null) marked = false;
                formals.add(new Formal("p" + i, option, marked, fallback));
            }
            java.util.ArrayList<Item> items = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                int j = rng.nextInt(n + 3);
                items.add(new Item(rng.nextBoolean() ? null : (j < n ? "p" + j : "unknown"),
                        rng.nextInt(4) == 0));
            }
            try {
                Plan plan = DefaultCallShapeV3.bind(formals, items);
                accepted++;
                java.util.ArrayList<Integer> provided = new java.util.ArrayList<>();
                for (int i = 0; i < items.size(); i++) if (!items.get(i).omission()) provided.add(i);
                assertEquals(provided, plan.evaluatedRawIndices());
                assertEquals(formals.size(), plan.abiSlots().size());
                java.util.HashSet<Integer> allRaw = new java.util.HashSet<>();
                for (Bound bound : plan.abiSlots()) {
                    if (bound.rawItemIndex() >= 0) {
                        assertTrue(allRaw.add(bound.rawItemIndex()), "raw source item aliased");
                    }
                }
                assertEquals(items.size(), allRaw.size(),
                        "every raw item (including omissions) must occur exactly once");
                for (int i = 0; i < plan.abiSlots().size(); i++) {
                    Bound slot = plan.abiSlots().get(i);
                    assertEquals(i, slot.formalIndex());
                    if (slot.origin() != Origin.PROVIDED) assertEquals(-1, slot.evaluatedIndex());
                }
            } catch (IllegalArgumentException rejected) {
                // Randomly constructed invalid call shapes should be rejected.
            }
        }
        assertTrue(accepted > 500, "regression harness must exercise admitted shapes");
    }
}
