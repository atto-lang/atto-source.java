package dev.oreslang;

import dev.oreslang.types.FixedProductCallPlan;
import dev.oreslang.types.OptionalCallPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.oreslang.types.FixedProductCallPlan.InputSlot.named;
import static dev.oreslang.types.FixedProductCallPlan.InputSlot.positional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OptionalCallPlanTest {
    @Test
    void namedArgumentsCanSkipNonTrailingOmittableSlot() {
        OptionalCallPlan plan = OptionalCallPlan.bind(
                List.of("a", "b", "c"),
                List.of(false, true, false),
                List.of(positional(), named("c")));

        assertEquals(List.of(0, -1, 1), plan.abiToSource());
        assertEquals(1, plan.omittedCount());
        assertEquals(
                List.of(0, 2),
                plan.sourceOrder().stream()
                        .map(OptionalCallPlan.BoundSlot::parameterIndex)
                        .toList());
    }

    @Test
    void trailingOmittableSlotsCanBeImplicitlyAbsent() {
        OptionalCallPlan plan = OptionalCallPlan.bind(
                List.of("a", "b", "c"),
                List.of(false, true, true),
                List.of(positional()));

        assertEquals(List.of(0, -1, -1), plan.abiToSource());
        assertEquals(2, plan.omittedCount());
    }

    @Test
    void lexicalSourceOrderSurvivesNamedReordering() {
        OptionalCallPlan plan = OptionalCallPlan.bind(
                List.of("a", "b", "c"),
                List.of(false, true, false),
                List.of(named("c"), named("a")));

        assertEquals(List.of(1, -1, 0), plan.abiToSource());
        assertEquals(
                List.of(0, 1),
                plan.sourceOrder().stream()
                        .map(OptionalCallPlan.BoundSlot::sourceIndex)
                        .toList());
    }

    @Test
    void missingRequiredDuplicateUnknownAndPositionalAfterNamedReject() {
        assertRejected(
                "missing required",
                List.of("a", "b", "c"),
                List.of(false, true, false),
                List.of(positional()));
        assertRejected(
                "duplicate",
                List.of("a", "b", "c"),
                List.of(false, true, false),
                List.of(positional(), named("a")));
        assertRejected(
                "unknown",
                List.of("a", "b", "c"),
                List.of(false, true, false),
                List.of(positional(), named("nope")));
        assertRejected(
                "positional",
                List.of("a", "b", "c"),
                List.of(false, true, false),
                List.of(named("c"), positional()));
    }

    @Test
    void publicConstructorCannotForgeOmittedOrBoundSlotMaps() {
        var source = List.of(
                new OptionalCallPlan.BoundSlot(0, 2, "c"),
                new OptionalCallPlan.BoundSlot(1, 0, "a"));

        OptionalCallPlan valid =
                new OptionalCallPlan(source, List.of(1, -1, 0));
        assertEquals(1, valid.omittedCount());

        assertThrows(
                IllegalArgumentException.class,
                () -> new OptionalCallPlan(source, List.of(0, -1, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OptionalCallPlan(
                        List.of(
                                new OptionalCallPlan.BoundSlot(0, 0, "a"),
                                new OptionalCallPlan.BoundSlot(0, 2, "c")),
                        List.of(0, -1, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OptionalCallPlan(
                        List.of(new OptionalCallPlan.BoundSlot(0, 0, "a")),
                        List.of(0, 4)));
    }

    @Test
    void zeroSourceArgumentsAreValidOnlyWhenEveryFormalIsOmittable() {
        OptionalCallPlan allOptional = OptionalCallPlan.bind(
                List.of("a", "b"),
                List.of(true, true),
                List.of());
        assertEquals(List.of(-1, -1), allOptional.abiToSource());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OptionalCallPlan.bind(
                        List.of("a", "b"),
                        List.of(true, false),
                        List.of()));
        assertTrue(failure.getMessage().contains("missing required"));
    }

    private static void assertRejected(
            String message,
            List<String> names,
            List<Boolean> omittable,
            List<FixedProductCallPlan.InputSlot> inputs) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OptionalCallPlan.bind(names, omittable, inputs));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }
}
