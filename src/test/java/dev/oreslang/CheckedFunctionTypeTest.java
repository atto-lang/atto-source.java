package dev.oreslang;

import dev.oreslang.types.Types;
import dev.oreslang.types.Types.CheckedEffects;
import dev.oreslang.types.Types.Function;
import dev.oreslang.types.Types.Primitive;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CheckedFunctionTypeTest {
    private static Function value(CheckedEffects effects) {
        return new Function(List.of(Primitive.INT), List.of(false), false,
                Primitive.STRING, effects);
    }

    @Test
    void functionEffectsAreImmutableSnapshots() {
        var mutable = new java.util.ArrayList<Types.Type>();
        mutable.add(Primitive.INT);
        var effects = new CheckedEffects(mutable, List.of(), List.of());
        var fn = value(effects);
        mutable.clear();
        assertEquals(List.of(Primitive.INT), fn.effects().throwsTypes());
        assertThrows(UnsupportedOperationException.class,
                () -> fn.effects().throwsTypes().clear());
    }

    @Test
    void functionEffectsSupportNarrowingButNeverErasure() {
        var allowed = new CheckedEffects(List.of(Primitive.STRING), List.of(Primitive.BOOL),
                List.of(Primitive.INT));
        var throwsOnly = new CheckedEffects(List.of(Primitive.STRING), List.of(), List.of());
        assertTrue(Types.isAssignable(value(throwsOnly), value(allowed)));
        assertFalse(Types.isAssignable(value(allowed), value(throwsOnly)));
        assertTrue(Types.isAssignable(value(CheckedEffects.none()), value(allowed)));
        assertFalse(Types.isAssignable(value(allowed), value(CheckedEffects.none())));
    }

    @Test
    void functionEffectsAreCategorySpecific() {
        var thrown = value(new CheckedEffects(List.of(Primitive.INT), List.of(), List.of()));
        var panicked = value(new CheckedEffects(List.of(), List.of(), List.of(Primitive.INT)));
        assertFalse(Types.isAssignable(thrown, panicked));
        assertFalse(Types.isAssignable(panicked, thrown));
    }

    @Test
    void unknownCannotManufactureOrEraseCheckedFunctionWitness() {
        var checked = value(new CheckedEffects(List.of(), List.of(),
                List.of(Primitive.INT)));
        assertFalse(Types.isAssignable(Types.Unknown.INSTANCE,
                value(CheckedEffects.none())));
        assertFalse(Types.isAssignable(checked, Types.Unknown.INSTANCE));
    }

    @Test
    void legacyPureFunctionConstructorsRemainPure() {
        var old = new Function(List.of(Primitive.INT), List.of(false), false, Primitive.STRING);
        var simple = new Function(List.of(Primitive.INT), Primitive.STRING);
        assertTrue(old.effects().isEmpty());
        assertTrue(simple.effects().isEmpty());
        assertEquals(old, simple);
    }

    @Test
    void functionParameterMutationRemainsInvariantAcrossEffectSubtyping() {
        var effects = new CheckedEffects(List.of(Primitive.INT), List.of(), List.of());
        var source = new Function(List.of(Primitive.INT), List.of(true), false,
                Primitive.STRING, effects);
        var target = value(effects);
        assertFalse(Types.isAssignable(source, target));
        assertFalse(Types.isAssignable(target, source));
    }
}
