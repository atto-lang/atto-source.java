package dev.oreslang;

import dev.oreslang.types.EffectContracts;
import dev.oreslang.types.EffectContracts.Call;
import dev.oreslang.types.EffectContracts.Callable;
import dev.oreslang.types.EffectContracts.Effect;
import dev.oreslang.types.EffectContracts.Graph;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Modern-main regression oracle for historical callable-effects PR #264.
 *
 * Covers the current closed-world effect graph without reintroducing the
 * obsolete `pure` lexer token or replacing the modern AST/parser/runtime.
 * Source-language syntax and executable Graal parity are separate gates.
 */
final class EffectContractsReconciliationTest {
    @Test
    void pureAllowsFailureEffectsButRejectsExternalWrites() {
        Graph graph = new Graph();
        graph.add(Callable.pure("module::safe",
                Set.of(Effect.THROW, Effect.RAISE, Effect.PANIC, Effect.CANCELLATION),
                List.of()));
        assertEquals(Set.of(Effect.THROW, Effect.RAISE, Effect.PANIC, Effect.CANCELLATION),
                graph.validate().get("module::safe"));

        Graph invalid = new Graph();
        invalid.add(Callable.pure("module::unsafe", Set.of(Effect.EXTERNAL_WRITE), List.of()));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, invalid::validate);
        assertTrue(error.getMessage().contains("EXTERNAL_WRITE"));
        assertTrue(error.getMessage().contains("module::unsafe"));
    }

    @Test
    void transitiveEffectsPreserveResolvedCallAncestry() {
        Graph graph = new Graph();
        graph.add(Callable.inferred("app::entry", Set.of(), List.of(Call.direct("app::helper"))));
        graph.add(Callable.inferred("app::helper", Set.of(), List.of(Call.direct("lib::write"))));
        graph.add(Callable.inferred("lib::write", Set.of(Effect.IO), List.of()));
        assertEquals(Set.of(Effect.IO), graph.infer().get("app::entry"));

        Graph protectedGraph = new Graph();
        protectedGraph.add(Callable.pure("app::entry", Set.of(), List.of(Call.direct("app::helper"))));
        protectedGraph.add(Callable.inferred("app::helper", Set.of(), List.of(Call.direct("lib::write"))));
        protectedGraph.add(Callable.inferred("lib::write", Set.of(Effect.IO), List.of()));
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, protectedGraph::validate);
        assertTrue(error.getMessage().contains("app::entry -> app::helper -> lib::write"));
    }

    @Test
    void trapSuppressesOnlyThrowAcrossCallableBoundary() {
        Graph graph = new Graph();
        graph.add(Callable.trapping("lib::trapped",
                Set.of(Effect.THROW, Effect.RAISE, Effect.PANIC, Effect.CANCELLATION),
                List.of()));
        graph.add(Callable.inferred("app::caller", Set.of(), List.of(Call.direct("lib::trapped"))));
        assertEquals(Set.of(Effect.RAISE, Effect.PANIC, Effect.CANCELLATION),
                graph.infer().get("app::caller"));
        assertEquals(Set.of(Effect.THROW, Effect.RAISE, Effect.PANIC, Effect.CANCELLATION),
                graph.infer().get("lib::trapped"));
    }

    @Test
    void trappingCannotLaunderIoOrUnknownCallsIntoPure() {
        Graph graph = new Graph();
        graph.add(Callable.trapping("lib::trapped",
                Set.of(Effect.THROW, Effect.IO), List.of(Call.direct("import::unknown"))));
        graph.add(Callable.pure("app::caller", Set.of(), List.of(Call.direct("lib::trapped"))));
        Set<Effect> inferred = graph.infer().get("app::caller");
        assertFalse(inferred.contains(Effect.THROW));
        assertTrue(inferred.contains(Effect.IO));
        assertTrue(inferred.contains(Effect.UNKNOWN_CALL));
        assertThrows(IllegalArgumentException.class, graph::validate);
    }

    @Test
    void mutuallyRecursiveGraphReachesEffectFixedPoint() {
        Graph graph = new Graph();
        graph.add(Callable.inferred("a", Set.of(Effect.IO), List.of(Call.direct("b"))));
        graph.add(Callable.inferred("b", Set.of(), List.of(Call.direct("c"))));
        graph.add(Callable.inferred("c", Set.of(Effect.PANIC), List.of(Call.direct("a"))));
        Map<String, Set<Effect>> result = graph.infer();
        for (String symbol : List.of("a", "b", "c")) {
            assertEquals(Set.of(Effect.IO, Effect.PANIC), result.get(symbol));
        }
    }

    @Test
    void unknownImportedTargetFailsClosedWithDiagnosticPath() {
        Graph graph = new Graph();
        graph.add(Callable.pure("app::entry", Set.of(), List.of(Call.direct("plugin::unknown"))));
        assertEquals(Set.of(Effect.UNKNOWN_CALL), graph.infer().get("app::entry"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, graph::validate);
        assertTrue(error.getMessage().contains("UNKNOWN_CALL"));
        assertTrue(error.getMessage().contains("app::entry -> plugin::unknown"));
    }

    @Test
    void validationDoesNotExposeMutableGraphOrEffectSets() {
        Graph graph = new Graph();
        graph.add(Callable.inferred("call", Set.of(Effect.IO), List.of()));
        Map<String, Set<Effect>> effects = graph.validate();
        assertThrows(UnsupportedOperationException.class,
                () -> effects.put("forged", Set.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> effects.get("call").clear());
    }

    @Test
    void rejectedOversizedCallListDoesNotPoisonAdmission() {
        Graph graph = new Graph();
        assertThrows(IllegalArgumentException.class,
                () -> graph.add(Callable.inferred("too-wide", Set.of(),
                        Collections.nCopies(1025, Call.direct("target")))));
        graph.add(Callable.inferred("valid", Set.of(), List.of()));
        assertEquals(Set.of(), graph.validate().get("valid"));
    }
}
