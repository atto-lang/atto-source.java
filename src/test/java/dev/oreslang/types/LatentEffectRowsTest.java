package dev.oreslang.types;

import org.junit.jupiter.api.Test;
import java.util.Set;

import static dev.oreslang.types.EffectContracts.Effect.*;
import static dev.oreslang.types.LatentEffectRows.*;
import static org.junit.jupiter.api.Assertions.*;

final class LatentEffectRowsTest {
    @Test void unknownDeferredActionsCannotClaimPurityByConstruction() {
        for (Kind kind : Kind.values()) {
            var unknown = LatentEffectRows.unresolved(kind);
            assertEquals(Set.of(), unknown.deferredConstructionEffects());
            assertTrue(unknown.latentEffects().contains(UNKNOWN_CALL));
            assertFalse(unknown.compilerProven());
            assertThrows(IllegalArgumentException.class,
                    () -> unknown.requireInvocationWithin(Set.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> unknown.requireInvocationWithin(Set.of(UNKNOWN_CALL)));
        }
    }

    @Test void knownIoActionIsPureToConstructButCheckedAtInvocation() {
        var action = LatentEffectRows.compilerProven(Kind.IO_ACTION, Set.of(IO), Capture.OWNED);
        assertEquals(Set.of(), action.deferredConstructionEffects());
        assertEquals(Set.of(IO), action.latentEffects());
        assertThrows(IllegalArgumentException.class,
                () -> action.requireInvocationWithin(Set.of()));
        assertEquals(Set.of(IO), action.requireInvocationWithin(Set.of(IO)));
    }

    @Test void bindAndContainerConversionRetainEveryLatentEffect() {
        var action = LatentEffectRows.compilerProven(Kind.IO_ACTION,
                Set.of(IO), Capture.OWNED);
        var mapper = LatentEffectRows.compilerProven(Kind.CALLABLE,
                Set.of(SUSPEND, THROW), Capture.OWNED);
        var composed = action.compose(mapper, Kind.IO_ACTION);
        assertEquals(Set.of(IO, SUSPEND, THROW), composed.latentEffects());
        assertEquals(composed.latentEffects(), composed.as(Kind.STREAM).latentEffects());
        assertEquals(composed.latentEffects(), composed.as(Kind.FUTURE).latentEffects());
        assertThrows(IllegalArgumentException.class,
                () -> composed.requireInvocationWithin(Set.of(IO, THROW)));
        assertEquals(composed.latentEffects(),
                composed.requireInvocationWithin(Set.of(IO, SUSPEND, THROW)));
    }

    @Test void unknownCallbackContaminatesOtherwiseProvenPureAction() {
        var pure = LatentEffectRows.compilerProven(Kind.IO_ACTION,
                Set.of(), Capture.OWNED);
        var result = pure.compose(LatentEffectRows.unresolved(Kind.CALLABLE),
                Kind.IO_ACTION);
        assertFalse(result.compilerProven());
        assertTrue(result.latentEffects().contains(UNKNOWN_CALL));
        assertThrows(IllegalArgumentException.class,
                () -> result.requireInvocationWithin(Set.of(IO)));
    }

    @Test void runtimeAffineCapturesCannotBeLaunderedIntoDeferredOperations() {
        var loan = LatentEffectRows.compilerProven(Kind.CALLABLE,
                Set.of(STATE_WRITE), Capture.ACTOR_STATE_LOAN);
        assertEquals(Set.of(STATE_WRITE), loan.requireInvocationWithin(Set.of(STATE_WRITE)));
        assertThrows(IllegalArgumentException.class,
                () -> loan.as(Kind.FUTURE).requireInvocationWithin(Set.of(STATE_WRITE)));
        assertThrows(IllegalArgumentException.class,
                () -> loan.as(Kind.IO_ACTION).requireInvocationWithin(Set.of(STATE_WRITE)));
        assertThrows(IllegalArgumentException.class,
                () -> loan.compose(LatentEffectRows.compilerProven(Kind.CALLABLE,
                        Set.of(SUSPEND), Capture.OWNED), Kind.CALLABLE)
                        .requireInvocationWithin(Set.of(STATE_WRITE, SUSPEND)));
    }

    @Test void borrowedAndActorLoanDescriptorsCannotBeRepackagedOrDeferred() {
        for (Capture capture : new Capture[]{Capture.BORROWED, Capture.ACTOR_STATE_LOAN}) {
            var source = LatentEffectRows.compilerProven(Kind.CALLABLE, Set.of(), capture);
            var owned = LatentEffectRows.compilerProven(Kind.CALLABLE, Set.of(), Capture.OWNED);
            assertThrows(IllegalArgumentException.class, () -> source.as(Kind.CALLABLE));
            assertThrows(IllegalArgumentException.class, () -> source.as(Kind.STREAM));
            assertThrows(IllegalArgumentException.class, () -> source.compose(owned, Kind.CALLABLE));
            assertThrows(IllegalArgumentException.class, () -> owned.compose(source, Kind.IO_ACTION));
            assertThrows(IllegalArgumentException.class, () -> owned.compose(source, Kind.CALLABLE));
            assertThrows(IllegalArgumentException.class, () ->
                    LatentEffectRows.compilerProven(Kind.FUTURE, Set.of(), capture));
        }
    }

}
