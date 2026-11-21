package dev.oreslang;

import dev.oreslang.types.EffectContracts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static dev.oreslang.types.EffectContracts.Effect.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Static-effect library admission tests, not source-code effect extraction.
 * The caller must supply trusted overload-resolved edges independently.
 */
final class EffectGraphAdmissionBoundaryTest {
    @Test void unknownExternalEffectsPropagateAcrossTransitiveCycles() {
        var graph = new EffectContracts.Graph();
        graph.add(EffectContracts.Callable.pure("entry", Set.of(),
                List.of(EffectContracts.Call.direct("helper"))));
        graph.add(EffectContracts.Callable.inferred("helper", Set.of(),
                List.of(EffectContracts.Call.direct("callee"), EffectContracts.Call.direct("entry"))));
        graph.add(EffectContracts.Callable.inferred("callee", Set.of(),
                List.of(EffectContracts.Call.direct("unlinked.host"))));
        var error = assertThrows(IllegalArgumentException.class, graph::validate);
        assertTrue(error.getMessage().contains("UNKNOWN_CALL"));
        assertTrue(error.getMessage().contains("entry -> helper -> callee -> unlinked.host"));
    }

    @Test void trapIsOnlyAnOrdinaryThrowBoundary() {
        var graph = new EffectContracts.Graph();
        graph.add(EffectContracts.Callable.trapping("handled",
                Set.of(THROW, RAISE, PANIC, CANCELLATION), List.of()));
        graph.add(EffectContracts.Callable.inferred("caller", Set.of(),
                List.of(EffectContracts.Call.direct("handled"))));
        assertEquals(Set.of(RAISE, PANIC, CANCELLATION), graph.infer().get("caller"));
    }

    @Test void aRejectedOverBudgetInsertionCannotChangeGraphMeaning() {
        var graph = new EffectContracts.Graph();
        graph.add(EffectContracts.Callable.pure("entry", Set.of(), List.of()));
        var excessiveCalls = new ArrayList<EffectContracts.Call>();
        for (int i = 0; i < 1025; i++) {
            excessiveCalls.add(EffectContracts.Call.direct("unknown"));
        }
        assertThrows(IllegalArgumentException.class, () -> graph.add(
                EffectContracts.Callable.inferred("hostile", Set.of(IO), excessiveCalls)));
        assertEquals(Set.of(), graph.validate().get("entry"));
        assertFalse(graph.infer().containsKey("hostile"));
    }

    @Test void oversizedOrMalformedSymbolsAreRejectedBeforeInference() {
        assertThrows(IllegalArgumentException.class, () ->
                EffectContracts.Call.direct("x".repeat(257)));
        assertThrows(IllegalArgumentException.class, () ->
                EffectContracts.Call.direct(" "));
        assertThrows(IllegalArgumentException.class, () ->
                EffectContracts.Callable.inferred("x".repeat(257), Set.of(), List.of()));
    }

    @Test void unresolvedCallbackNeverAliasesExistingPureSymbol() {
        var graph = new EffectContracts.Graph();
        graph.add(EffectContracts.Callable.pure("entry", Set.of(),
                List.of(EffectContracts.Call.unresolved("same.name"))));
        graph.add(EffectContracts.Callable.inferred("same.name", Set.of(), List.of()));
        assertEquals(Set.of(UNKNOWN_CALL), graph.infer().get("entry"));
        var error = assertThrows(IllegalArgumentException.class, graph::validate);
        assertTrue(error.getMessage().contains("entry -> [unresolved] same.name"));

        var legacy = new EffectContracts.Graph();
        legacy.add(EffectContracts.Callable.pure("entry", Set.of(),
                List.of(new EffectContracts.Call("same.name"))));
        legacy.add(EffectContracts.Callable.inferred("same.name", Set.of(), List.of()));
        assertEquals(Set.of(UNKNOWN_CALL), legacy.infer().get("entry"));
        // A compiler-resolved direct invocation is a separate kind of edge.
        var direct = new EffectContracts.Graph();
        direct.add(EffectContracts.Callable.pure("entry", Set.of(),
                List.of(EffectContracts.Call.direct("same.name"))));
        direct.add(EffectContracts.Callable.inferred("same.name", Set.of(), List.of()));
        assertEquals(Set.of(), direct.validate().get("entry"));
    }

    @Test void trappingCannotEraseUnresolvedDispatchEvenWithThrow() {
        var graph = new EffectContracts.Graph();
        graph.add(EffectContracts.Callable.trapping("trap", Set.of(THROW),
                List.of(EffectContracts.Call.unresolved("fake.target"))));
        graph.add(EffectContracts.Callable.inferred("fake.target", Set.of(), List.of()));
        graph.add(EffectContracts.Callable.inferred("entry", Set.of(),
                List.of(EffectContracts.Call.direct("trap"))));
        assertEquals(Set.of(UNKNOWN_CALL), graph.infer().get("entry"));
    }

    @Test void controlAndUnicodeFormatCharactersCannotSpoofEffectWitnesses() {
        assertThrows(IllegalArgumentException.class,
                () -> EffectContracts.Call.direct("function" + (char) 10 + "IO"));
        assertThrows(IllegalArgumentException.class,
                () -> EffectContracts.Call.unresolved("callback" + (char) 13));
        assertThrows(IllegalArgumentException.class,
                () -> EffectContracts.Callable.inferred("name" + (char) 0x200B,
                        Set.of(), List.of()));
        assertThrows(NullPointerException.class,
                () -> new EffectContracts.Call("target", null));
    }

    @Test void declaredUnknownEffectCannotBecomeAnEscapeHatch() {
        var graph = new EffectContracts.Graph();
        graph.add(new EffectContracts.Callable("entry", Set.of(),
                List.of(EffectContracts.Call.unresolved("dynamic")),
                Set.of(UNKNOWN_CALL)));
        var rejected = assertThrows(IllegalArgumentException.class, graph::validate);
        assertTrue(rejected.getMessage().contains("unverifiable effect allowance"));
        var foreign = new EffectContracts.Graph();
        foreign.add(new EffectContracts.Callable("entry", Set.of(FFI_UNKNOWN),
                List.of(), Set.of(FFI_UNKNOWN)));
        assertThrows(IllegalArgumentException.class, foreign::validate);
    }
}
