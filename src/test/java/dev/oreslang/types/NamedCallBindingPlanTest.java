package dev.oreslang.types;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NamedCallBindingPlanTest {
    private static final List<String> PARAMS = List.of("left", "right");

    @Test
    void positionalCallsKeepDeclarationOrder() {
        var plan = NamedCallBindingPlan.bind(PARAMS, Arrays.asList(null, null));
        assertEquals(List.of(0, 1), plan.sourceToFormal());
        assertEquals(List.of(10, 20), plan.inFormalOrder(List.of(10, 20)));
    }

    @Test
    void reversedLabelsPreserveEvaluationOrderButReorderEvaluatedValues() {
        var plan = NamedCallBindingPlan.bind(PARAMS, List.of("right", "left"));
        assertEquals(List.of(1, 0), plan.sourceToFormal());
        assertEquals(List.of(1, 0), plan.formalToSource());
        assertEquals(List.of(10, 20), plan.inFormalOrder(List.of(20, 10)));
    }

    @Test
    void positionalPrefixThenNamedArgumentsAreAccepted() {
        var plan = NamedCallBindingPlan.bind(PARAMS, Arrays.asList(null, "right"));
        assertEquals(List.of(0, 1), plan.sourceToFormal());
    }

    @Test
    void rejectsUnknownOrCaseMismatchedLabel() {
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bind(PARAMS, List.of("unknown", "right")));
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bind(PARAMS, List.of("LEFT", "right")));
    }

    @Test
    void rejectsDuplicateNamedOrPositionalCollision() {
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bind(PARAMS, List.of("left", "left")));
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bind(PARAMS, Arrays.asList(null, "left")));
    }

    @Test
    void rejectsMissingOrExcessArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bind(PARAMS, List.of("left")));
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bind(PARAMS, Arrays.asList(null, null, null)));
    }

    @Test
    void rejectsPositionalArgumentFollowingNamedArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bind(PARAMS, Arrays.asList("right", null)));
    }

    @Test
    void rejectsInvalidDeclaredParameterNamesAndEmptyCallLabels() {
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bind(List.of("same", "same"), List.of("same", "same")));
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bind(PARAMS, List.of("", "right")));
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bind(Arrays.asList(" ", "right"), List.of(" ", "right")));
    }

    @Test
    void bindingPlanAndResultAreImmutable() {
        var plan = NamedCallBindingPlan.bind(PARAMS, List.of("right", "left"));
        assertThrows(UnsupportedOperationException.class, () -> plan.sourceToFormal().add(99));
        assertThrows(UnsupportedOperationException.class, () -> plan.inFormalOrder(List.of(20, 10)).add(99));
        assertThrows(IllegalArgumentException.class, () -> plan.inFormalOrder(List.of(10)));
    }

    @Test
    void evaluatedNullValuesRetainSlotIdentity() {
        var plan = NamedCallBindingPlan.bind(PARAMS, List.of("right", "left"));
        assertEquals(Arrays.asList("left-value", null),
                plan.inFormalOrder(Arrays.asList(null, "left-value")));
    }

    @Test
    void rejectsMalformedDirectlyConstructedPermutation() {
        assertThrows(IllegalArgumentException.class,
                () -> new NamedCallBindingPlan.Plan(List.of(0, 0), List.of(0, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new NamedCallBindingPlan.Plan(List.of(0, 1), List.of(1, 0)));
    }

    @Test
    void zeroArgumentCallableHasEmptyPlan() {
        var plan = NamedCallBindingPlan.bind(List.of(), List.of());
        assertEquals(List.of(), plan.inFormalOrder(List.of()));
    }
}
