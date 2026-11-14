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

    @Test
    void callbackSlotIsUnknownEvenWhenAFunctionHasTheSameName() {
        Graph graph = new Graph();
        graph.add(Callable.inferred("library::map", Set.of(), List.of(Call.callback("mapper"))));
        graph.add(Callable.inferred("mapper", Set.of(), List.of()));

        assertEquals(Set.of(Effect.UNKNOWN_CALL), graph.infer().get("library::map"),
                "a lexical callback slot must not silently resolve as a function symbol");
        assertEquals(Set.of(), graph.inferInvocation("library::map", Map.of("mapper", "mapper")));
        assertEquals(Set.of(Effect.UNKNOWN_CALL),
                graph.inferInvocation("library::map", Map.of()),
                "unbound invocation must fail closed");
    }

    @Test
    void pureHigherOrderFunctionInheritsCallbackEffectsPerInvocation() {
        Graph graph = new Graph();
        graph.add(Callable.pure("library::map", Set.of(Effect.ALLOC),
                List.of(Call.callback("transform"))));
        graph.add(Callable.inferred("app::pure_transform", Set.of(), List.of()));
        graph.add(Callable.inferred("app::logging_transform", Set.of(Effect.IO), List.of()));

        assertEquals(Set.of(Effect.ALLOC),
                graph.validateInvocation("library::map",
                        Map.of("transform", "app::pure_transform")));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> graph.validateInvocation("library::map",
                        Map.of("transform", "app::logging_transform")));
        assertTrue(error.getMessage().contains("IO"));
        assertTrue(error.getMessage().contains("library::map -> app::logging_transform"));
        assertEquals(Set.of(Effect.UNKNOWN_CALL, Effect.ALLOC),
                graph.infer().get("library::map"),
                "specialization must not mutate the shared definition graph");
    }

    @Test
    void callbackEffectsPropagateTransitivelyAndAcrossRecursiveCycles() {
        Graph graph = new Graph();
        graph.add(Callable.pure("library::filter", Set.of(),
                List.of(Call.callback("predicate"))));
        graph.add(Callable.inferred("app::predicate", Set.of(),
                List.of(Call.direct("app::helper"))));
        graph.add(Callable.inferred("app::helper", Set.of(),
                List.of(Call.direct("app::predicate"), Call.direct("app::io"))));
        graph.add(Callable.inferred("app::io", Set.of(Effect.IO), List.of()));

        assertTrue(graph.inferInvocation("library::filter",
                Map.of("predicate", "app::predicate")).contains(Effect.IO));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> graph.validateInvocation("library::filter",
                        Map.of("predicate", "app::predicate")));
        assertTrue(error.getMessage().contains(
                "library::filter -> app::predicate -> app::helper -> app::io"));
    }

    @Test
    void unknownMissingOrInventedCallbackBindingsFailClosed() {
        Graph graph = new Graph();
        graph.add(Callable.pure("library::fold", Set.of(),
                List.of(Call.callback("step"))));

        assertTrue(graph.inferInvocation("library::fold", Map.of())
                .contains(Effect.UNKNOWN_CALL));
        assertThrows(IllegalArgumentException.class,
                () -> graph.validateInvocation("library::fold", Map.of()));
        assertTrue(graph.inferInvocation("library::fold",
                Map.of("step", "missing::function")).contains(Effect.UNKNOWN_CALL));
        assertThrows(IllegalArgumentException.class,
                () -> graph.validateInvocation("library::fold",
                        Map.of("step", "missing::function")));
        assertThrows(IllegalArgumentException.class,
                () -> graph.inferInvocation("library::fold", Map.of("other", "target")));
        assertThrows(IllegalArgumentException.class,
                () -> Call.callback("not.a.parameter"));
    }

    @Test
    void callbackBindingRespectsTrapAndKeepsPanicVisible() {
        Graph graph = new Graph();
        graph.add(Callable.inferred("library::apply", Set.of(),
                List.of(Call.callback("callback"))));
        graph.add(Callable.trapping("app::handled",
                Set.of(Effect.THROW, Effect.PANIC), List.of()));

        assertEquals(Set.of(Effect.PANIC),
                graph.inferInvocation("library::apply", Map.of("callback", "app::handled")));
        assertEquals(Set.of(Effect.THROW, Effect.PANIC),
                graph.infer().get("app::handled"));
    }

    @Test
    void unrelatedCallbackSlotsAreNotAccidentallyBound() {
        Graph graph = new Graph();
        graph.add(Callable.inferred("library::first", Set.of(),
                List.of(Call.callback("fn"))));
        graph.add(Callable.inferred("library::second", Set.of(),
                List.of(Call.callback("fn"))));
        graph.add(Callable.inferred("app::pure", Set.of(), List.of()));

        assertEquals(Set.of(), graph.inferInvocation("library::first",
                Map.of("fn", "app::pure")));
        assertEquals(Set.of(Effect.UNKNOWN_CALL), graph.infer().get("library::second"));
        assertEquals(Set.of(Effect.UNKNOWN_CALL),
                graph.inferInvocation("library::second", Map.of()));
    }
}
