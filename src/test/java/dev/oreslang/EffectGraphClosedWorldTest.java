package dev.oreslang;

import dev.oreslang.types.EffectContracts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dev.oreslang.types.EffectContracts.Effect.*;
import static org.junit.jupiter.api.Assertions.*;

final class EffectGraphClosedWorldTest {
    @Test void unknownUnboundedCallDoesNotBecomeAnAdmissionCertificate() {
        var graph = new EffectContracts.Graph();
        graph.add(EffectContracts.Callable.inferred("entry", Set.of(),
                List.of(EffectContracts.Call.unresolved("unknown"))));
        // Existing bound validation is a useful analysis API but NOT proof.
        assertEquals(Set.of(UNKNOWN_CALL), graph.validate().get("entry"));
        var denied = assertThrows(IllegalArgumentException.class,
                graph::validateClosedWorld);
        assertTrue(denied.getMessage().contains("[unresolved] unknown"));
    }

    @Test void missingResolvedSymbolAndFfiUnknownAreRejectedWithoutBounds() {
        var missing = new EffectContracts.Graph();
        missing.add(EffectContracts.Callable.inferred("entry", Set.of(),
                List.of(EffectContracts.Call.direct("not.linked"))));
        assertThrows(IllegalArgumentException.class, missing::validateClosedWorld);

        var ffi = new EffectContracts.Graph();
        ffi.add(EffectContracts.Callable.inferred("external", Set.of(FFI_UNKNOWN), List.of()));
        ffi.add(EffectContracts.Callable.inferred("entry", Set.of(),
                List.of(EffectContracts.Call.direct("external"))));
        var rejected = assertThrows(IllegalArgumentException.class, ffi::validateClosedWorld);
        assertTrue(rejected.getMessage().contains("FFI_UNKNOWN"));
    }

    @Test void directGraphCyclesRemainAnalyzableButCannotClaimCompilerEvidence() {
        var graph = new EffectContracts.Graph();
        graph.add(EffectContracts.Callable.inferred("a", Set.of(),
                List.of(EffectContracts.Call.direct("b"))));
        graph.add(EffectContracts.Callable.inferred("b", Set.of(IO),
                List.of(EffectContracts.Call.direct("a"))));
        var inferred = graph.validate();
        assertEquals(Set.of(IO), inferred.get("a"));
        assertEquals(Set.of(IO), inferred.get("b"));
        assertThrows(UnsupportedOperationException.class,
                () -> inferred.put("forged", Set.of()));
        var denied = assertThrows(IllegalArgumentException.class,
                graph::validateClosedWorld);
        assertTrue(denied.getMessage().contains("compiler-resolved provenance"));
    }

    @Test void forgedDirectCallToPureSymbolCannotMintAnAdmission() {
        var graph = new EffectContracts.Graph();
        graph.add(EffectContracts.Callable.inferred("entry", Set.of(),
                List.of(EffectContracts.Call.direct("pure"))));
        graph.add(EffectContracts.Callable.inferred("pure", Set.of(), List.of()));
        assertTrue(graph.validate().get("entry").isEmpty());
        assertThrows(IllegalArgumentException.class, graph::validateClosedWorld);
    }

    @Test void spoofedTrapCannotEraseAnExceptionWithoutCompilerProvenance() {
        var graph = new EffectContracts.Graph();
        graph.add(EffectContracts.Callable.trapping("handler", Set.of(THROW), List.of()));
        assertThrows(IllegalArgumentException.class, graph::validateClosedWorld);
    }

    @Test void VerifiedCallFreeGraphStillAllowsKnownEffects() {
        var graph = new EffectContracts.Graph();
        graph.add(EffectContracts.Callable.inferred("allocate", Set.of(ALLOC), List.of()));
        assertEquals(Set.of(ALLOC), graph.validateClosedWorld().get("allocate"));
    }

    @Test void emptyGraphCannotProveAnyProgram() {
        var graph = new EffectContracts.Graph();
        assertTrue(graph.validate().isEmpty());
        assertThrows(IllegalArgumentException.class, graph::validateClosedWorld);
    }
}
