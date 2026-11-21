package dev.oreslang;

import dev.oreslang.types.ConstructorFailureCleanup;
import dev.oreslang.types.ConstructorFlowState;
import dev.oreslang.types.ConstructorSemanticIr;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ConstructorFailureCleanupTest {
    private static ConstructorSemanticIr.ConstructionRegionId region() {
        return new ConstructorSemanticIr.ConstructionRegionId("Foo", 1, 42);
    }

    private static ConstructorSemanticIr.ConstructionState state(
            ConstructorFlowState fields,
            List<ConstructorSemanticIr.CleanupEdge> pending) {
        return new ConstructorSemanticIr.ConstructionState(
                region(),
                ConstructorSemanticIr.ConstructionPhase.INITIALIZING,
                fields,
                pending,
                Set.of("a", "b"));
    }

    @Test
    void failureUsesLifoCleanupThenReverseDeclarationOrderThenBaseThenStorage() {
        ConstructorFlowState fields = ConstructorFlowState.blank(List.of("a", "b", "c"))
                .write("a").write("b");
        var early = new ConstructorSemanticIr.CleanupEdge(
                ConstructorSemanticIr.CleanupKind.DEFER, "defer-first");
        var late = new ConstructorSemanticIr.CleanupEdge(
                ConstructorSemanticIr.CleanupKind.FINALLY, "finally-second");
        var state = state(fields, List.of(early, late));

        assertEquals(List.of(
                new ConstructorFailureCleanup.Action(
                        ConstructorSemanticIr.CleanupKind.FINALLY, "finally-second", false),
                new ConstructorFailureCleanup.Action(
                        ConstructorSemanticIr.CleanupKind.DEFER, "defer-first", false),
                new ConstructorFailureCleanup.Action(
                        ConstructorSemanticIr.CleanupKind.FIELD_DROP, "b", false),
                new ConstructorFailureCleanup.Action(
                        ConstructorSemanticIr.CleanupKind.FIELD_DROP, "a", false),
                new ConstructorFailureCleanup.Action(
                        ConstructorSemanticIr.CleanupKind.BASE_DROP, "Foo", false),
                new ConstructorFailureCleanup.Action(
                        ConstructorSemanticIr.CleanupKind.STORAGE_RECLAIM, "Foo", false)),
                ConstructorFailureCleanup.plan(state, List.of("a", "b", "c"), true));
    }

    @Test
    void joinedMaybeInitializationUsesGuardedFieldDrop() {
        var blank = ConstructorFlowState.blank(List.of("a", "b"));
        var joined = blank.join(blank.write("a"));
        var actions = ConstructorFailureCleanup.plan(
                state(joined, List.of()), List.of("a", "b"), false);
        assertEquals(List.of(
                new ConstructorFailureCleanup.Action(
                        ConstructorSemanticIr.CleanupKind.FIELD_DROP, "a", true),
                new ConstructorFailureCleanup.Action(
                        ConstructorSemanticIr.CleanupKind.STORAGE_RECLAIM, "Foo", false)),
                actions);
    }

    @Test
    void repeatedMutableAssignmentsStillDropCurrentSlotOnlyOnce() {
        var twice = ConstructorFlowState.blank(List.of("a", "b"))
                .write("a").write("a").write("b");
        var actions = ConstructorFailureCleanup.plan(
                state(twice, List.of()), List.of("a", "b"), false);
        assertEquals(2, actions.stream()
                .filter(x -> x.kind() == ConstructorSemanticIr.CleanupKind.FIELD_DROP).count());
        assertFalse(actions.stream().anyMatch(ConstructorFailureCleanup.Action::conditional));
    }

    @Test
    void constructionWithNoInitializedFieldsStillReclaimsStorage() {
        var blank = ConstructorFlowState.blank(List.of("a", "b"));
        assertEquals(List.of(new ConstructorFailureCleanup.Action(
                ConstructorSemanticIr.CleanupKind.STORAGE_RECLAIM, "Foo", false)),
                ConstructorFailureCleanup.plan(state(blank, List.of()), List.of("a", "b"), false));
    }

    @Test
    void cannotCleanupConstructedObjectAsFailedConstruction() {
        var complete = ConstructorFlowState.blank(List.of("a", "b"))
                .write("a").write("b");
        var constructed = new ConstructorSemanticIr.ConstructionState(
                region(), ConstructorSemanticIr.ConstructionPhase.CONSTRUCTED,
                complete, List.of(), Set.of("a", "b"));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructorFailureCleanup.plan(
                        constructed, List.of("a", "b"), true));
    }

    @Test
    void rejectsAmbiguousFieldLayoutsAndDoublyPlannedResourceDrops() {
        var blank = ConstructorFlowState.blank(List.of("a", "b"));
        assertThrows(IllegalArgumentException.class, () ->
                ConstructorFailureCleanup.plan(state(blank, List.of()), List.of("a", "a"), false));
        assertThrows(IllegalArgumentException.class, () ->
                ConstructorFailureCleanup.plan(state(blank, List.of()), List.of("a"), false));
        assertThrows(IllegalArgumentException.class, () ->
                ConstructorFailureCleanup.plan(state(blank, List.of(
                        new ConstructorSemanticIr.CleanupEdge(
                                ConstructorSemanticIr.CleanupKind.FIELD_DROP, "a"))),
                        List.of("a", "b"), false));
    }

    @Test
    void returnedCleanupPlanIsImmutable() {
        var blank = ConstructorFlowState.blank(List.of("a", "b"));
        var actions = ConstructorFailureCleanup.plan(
                state(blank, List.of()), List.of("a", "b"), false);
        assertThrows(UnsupportedOperationException.class,
                () -> actions.add(new ConstructorFailureCleanup.Action(
                        ConstructorSemanticIr.CleanupKind.DEFER, "extra", false)));
    }
}
