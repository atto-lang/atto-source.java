package dev.oreslang;

import dev.oreslang.types.EffectContracts;
import dev.oreslang.types.RefinementProofs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dev.oreslang.types.EffectContracts.Effect.*;
import static org.junit.jupiter.api.Assertions.*;

final class RefinementProofsTest {
    private static RefinementProofs.Interval r(long lo, long hi) {
        return new RefinementProofs.Interval(lo, hi);
    }

    @Test void intervalInclusionProvesWholeValueDomainNotOneExample() {
        assertTrue(r(5, 12).entails(r(0, 20)));
        assertFalse(r(-1, 12).entails(r(0, 20)));
        assertFalse(RefinementProofs.Interval.anyI64().entails(r(0, 100)));
        assertThrows(IllegalArgumentException.class, () -> r(10, 3));
    }

    @Test void additionSubtractionAndMultiplicationUseMathematicalBounds() {
        assertEquals(r(3, 7), r(1, 3).plus(r(2, 4)));
        assertEquals(r(-3, 1), r(1, 3).minus(r(2, 4)));
        assertEquals(r(-12, 12), r(-2, 3).times(r(-4, 4)));
        assertThrows(IllegalArgumentException.class, () ->
                r(Long.MAX_VALUE, Long.MAX_VALUE).plus(r(1, 1)));
        assertThrows(IllegalArgumentException.class, () ->
                r(Long.MIN_VALUE, Long.MIN_VALUE).minus(r(1, 1)));
        assertThrows(IllegalArgumentException.class, () ->
                r(Long.MIN_VALUE, Long.MIN_VALUE).times(r(-1, -1)));
        assertEquals(r(Long.MIN_VALUE, Long.MAX_VALUE),
                RefinementProofs.Interval.anyI64());
    }

    @Test void contractRejectsUnprovedPreconditionsAndInferredEffects() {
        var contract = new RefinementProofs.FunctionContract("ledger.charge",
                List.of(r(0, 100), r(1, 20)), r(0, 2000), Set.of(IO));
        RefinementProofs.requireCall(contract, List.of(r(1, 10), r(2, 3)), Set.of(IO));
        assertThrows(IllegalArgumentException.class, () ->
                RefinementProofs.requireCall(contract, List.of(r(-1, 10), r(2, 3)), Set.of(IO)));
        assertThrows(IllegalArgumentException.class, () ->
                RefinementProofs.requireCall(contract, List.of(r(1, 10), r(2, 3)), Set.of(IO, PANIC)));
        assertThrows(IllegalArgumentException.class, () ->
                RefinementProofs.requireCall(contract, List.of(r(1, 10)), Set.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new RefinementProofs.FunctionContract("bad", List.of(), r(0, 0), Set.of(UNKNOWN_CALL)));
    }

    @Test void everySuppliedNormalReturnMustProvePostcondition() {
        var contract = new RefinementProofs.FunctionContract("ledger.total",
                List.of(), r(0, 100), Set.of());
        RefinementProofs.requireReturns(contract, List.of(r(0, 20), r(20, 90)));
        assertThrows(IllegalArgumentException.class, () ->
                RefinementProofs.requireReturns(contract, List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                RefinementProofs.requireReturns(contract, List.of(r(0, 20), r(-1, 90))));
    }
}
