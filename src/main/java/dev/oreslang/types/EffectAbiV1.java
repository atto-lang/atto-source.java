package dev.oreslang.types;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.oreslang.types.EffectContracts.Effect;

/**
 * Closed canonical source effect vocabulary shared with native
 * oreslang::FunctionEffects. A strict *translation* of effects, not a
 * certificate that a compiler actually inferred all guest operations.
 *
 * In particular, Java-only observations (AMBIENT_READ, EXTERNAL_WRITE,
 * UNKNOWN_CALL, FFI_UNKNOWN, ACTOR, SCHEDULER, CANCELLATION, etc.) have
 * no direct native bit and MUST be rejected rather than silently dropped.
 */
public final class EffectAbiV1 {
    private EffectAbiV1() { }

    public static final int KNOWN_MASK = 0x3ff;

    private static final Map<Effect, Integer> BITS;
    private static final Map<String, Effect> NAMES;

    static {
        EnumMap<Effect, Integer> bits = new EnumMap<>(Effect.class);
        bits.put(Effect.STATE_READ, 1 << 0);
        bits.put(Effect.STATE_WRITE, 1 << 1);
        bits.put(Effect.SUSPEND, 1 << 2);
        bits.put(Effect.IO, 1 << 3);
        bits.put(Effect.ALLOC, 1 << 4);
        bits.put(Effect.BLOCK, 1 << 5);
        bits.put(Effect.FFI, 1 << 6);
        bits.put(Effect.THROW, 1 << 7);
        bits.put(Effect.RAISE, 1 << 8);
        bits.put(Effect.PANIC, 1 << 9);
        BITS = Collections.unmodifiableMap(bits);

        Map<String, Effect> names = new LinkedHashMap<>();
        names.put("state.read", Effect.STATE_READ);
        names.put("state.write", Effect.STATE_WRITE);
        names.put("suspend", Effect.SUSPEND);
        names.put("io", Effect.IO);
        names.put("alloc", Effect.ALLOC);
        names.put("block", Effect.BLOCK);
        names.put("ffi", Effect.FFI);
        names.put("throw", Effect.THROW);
        names.put("raise", Effect.RAISE);
        names.put("panic", Effect.PANIC);
        NAMES = Collections.unmodifiableMap(names);
        if (BITS.size() != 10 || NAMES.size() != 10) {
            throw new ExceptionInInitializerError("reviewed effect ABI table changed");
        }
    }

    public static int encode(Set<Effect> effects) {
        Objects.requireNonNull(effects, "effects");
        int mask = 0;
        for (Effect effect : effects) {
            Integer bit = BITS.get(Objects.requireNonNull(effect, "effect"));
            if (bit == null) {
                throw new IllegalArgumentException(
                        "effect lacks certified native ABI mapping: " + effect);
            }
            mask |= bit;
        }
        return mask;
    }

    public static Set<Effect> decode(int mask) {
        if ((mask & ~KNOWN_MASK) != 0) {
            throw new IllegalArgumentException("unknown or unresolved native effect bits");
        }
        EnumSet<Effect> decoded = EnumSet.noneOf(Effect.class);
        for (Map.Entry<Effect, Integer> entry : BITS.entrySet()) {
            if ((mask & entry.getValue()) != 0) decoded.add(entry.getKey());
        }
        return Collections.unmodifiableSet(decoded);
    }

    public static Effect parseCanonical(String spelling) {
        Effect result = NAMES.get(spelling);
        if (result == null) throw new IllegalArgumentException(
                "unknown or noncanonical native effect: " + spelling);
        return result;
    }

    public static Map<String, Effect> canonicalNames() { return NAMES; }
}
