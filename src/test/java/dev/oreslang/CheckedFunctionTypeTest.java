package dev.oreslang;

import dev.oreslang.types.Types;
import dev.oreslang.types.Types.CheckedEffects;
import dev.oreslang.types.Types.Function;
import dev.oreslang.types.Types.Primitive;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler-host type contracts only; not source exceptional execution. */
final class CheckedFunctionTypeTest {
    private static Function fn(CheckedEffects effects) {
        return new Function(List.of(Primitive.INT), List.of(false), false,
                Primitive.STRING, effects);
    }

    @Test
    void effectsAreIndependentImmutableSnapshots() {
        var mutable = new java.util.ArrayList<Types.Type>(List.of(Primitive.INT));
        var effect = new CheckedEffects(mutable, List.of(), List.of());
        mutable.clear();
        assertEquals(List.of(Primitive.INT), effect.throwsTypes());
        assertThrows(UnsupportedOperationException.class,
                () -> effect.throwsTypes().clear());
    }

    @Test
    void effectDeclarationOrderAndDuplicatesAreCanonical() {
        var first = new CheckedEffects(
                List.of(Primitive.INT, Primitive.BOOL, Primitive.INT),
                List.of(Primitive.STRING), List.of());
        var second = new CheckedEffects(
                List.of(Primitive.BOOL, Primitive.INT),
                List.of(Primitive.STRING), List.of());
        assertEquals(first, second);
        assertEquals(2, first.throwsTypes().size());
        assertEquals(fn(first), fn(second));
    }

    @Test
    void unknownCannotSynthesizeOrEraseCheckedFunctionTypes() {
        var effectful = fn(new CheckedEffects(
                List.of(), List.of(), List.of(Primitive.INT)));
        assertFalse(Types.isAssignable(Types.Unknown.INSTANCE, fn(CheckedEffects.none())));
        assertFalse(Types.isAssignable(Types.Unknown.INSTANCE, effectful));
        assertFalse(Types.isAssignable(effectful, Types.Unknown.INSTANCE));
    }

    @Test
    void existingConstructorsProducePureFunctions() {
        var old = new Function(List.of(Primitive.INT), List.of(false), false, Primitive.STRING);
        var simple = new Function(List.of(Primitive.INT), Primitive.STRING);
        assertEquals(old, simple);
        assertTrue(old.effects().isEmpty());
        assertTrue(simple.effects().isEmpty());
    }

    @Test
    void effectSubsetAssignmentIsCovariant() {
        var wide = new CheckedEffects(List.of(Primitive.STRING),
                List.of(Primitive.BOOL), List.of(Primitive.INT));
        var narrow = new CheckedEffects(List.of(Primitive.STRING), List.of(), List.of());
        assertTrue(Types.isAssignable(fn(narrow), fn(wide)));
        assertFalse(Types.isAssignable(fn(wide), fn(narrow)));
        assertTrue(Types.isAssignable(fn(CheckedEffects.none()), fn(wide)));
        assertFalse(Types.isAssignable(fn(wide), fn(CheckedEffects.none())));
    }

    @Test
    void checkedCategoriesCannotSubstituteForEachOther() {
        var thrown = fn(new CheckedEffects(List.of(Primitive.INT), List.of(), List.of()));
        var panicked = fn(new CheckedEffects(List.of(), List.of(), List.of(Primitive.INT)));
        var raised = fn(new CheckedEffects(List.of(), List.of(Primitive.INT), List.of()));
        assertFalse(Types.isAssignable(thrown, panicked));
        assertFalse(Types.isAssignable(raised, thrown));
        assertFalse(Types.isAssignable(panicked, raised));
    }

    @Test
    void mutableFunctionParametersStayInvariant() {
        var effect = new CheckedEffects(List.of(Primitive.INT), List.of(), List.of());
        var mutable = new Function(List.of(Primitive.INT), List.of(true), false,
                Primitive.STRING, effect);
        var immutable = fn(effect);
        assertFalse(Types.isAssignable(mutable, immutable));
        assertFalse(Types.isAssignable(immutable, mutable));
    }

    @Test
    void asyncEffectCarriersCannotBecomeSynchronous() {
        var effect = new CheckedEffects(List.of(), List.of(), List.of(Primitive.INT));
        var async = new Function(List.of(Primitive.INT), List.of(false), true,
                Primitive.STRING, effect);
        assertFalse(Types.isAssignable(async, fn(effect)));
        assertFalse(Types.isAssignable(fn(effect), async));
    }

    @Test
    void cannotUseNullEffectMetadata() {
        assertThrows(NullPointerException.class, () ->
                new Function(List.of(), List.of(), false, Primitive.VOID, null));
    }
}
