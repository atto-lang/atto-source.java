package dev.oreslang.types;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Backend-neutral core effect row (v1). The source parser may use this to
 * validate canonical effect names. No runtime behavior is implied.
 *
 * Exceptional control is distinct: THROW, RAISE and PANIC are NOT equivalent.
 * PURE is a separate contract and is never inferred from absence of ALLOC.
 */
public final class FunctionEffects {
    public enum Effect {
        STATE_READ("state.read"),
        STATE_WRITE("state.write"),
        SUSPEND("suspend"),
        IO("io"),
        ALLOC("alloc"),
        BLOCK("block"),
        FFI("ffi"),
        THROW("throw"),
        RAISE("raise"),
        PANIC("panic");
        private final String sourceName;
        Effect(String sourceName) { this.sourceName = sourceName; }
        public String sourceName() { return sourceName; }
    }

    private final Set<Effect> effects;
    private FunctionEffects(Set<Effect> effects) {
        this.effects = Collections.unmodifiableSet(EnumSet.copyOf(effects));
    }

    public static FunctionEffects empty() {
        return new FunctionEffects(EnumSet.noneOf(Effect.class));
    }

    public static Effect parseEffect(String sourceName) {
        for (Effect effect : Effect.values()) {
            if (effect.sourceName.equals(sourceName)) return effect;
        }
        throw new IllegalArgumentException("unknown Oreslang effect '" + sourceName + "'");
    }

    public static FunctionEffects ofNames(List<String> names) {
        EnumSet<Effect> set = EnumSet.noneOf(Effect.class);
        for (String name : names) {
            Effect effect = parseEffect(name);
            if (!set.add(effect)) {
                throw new IllegalArgumentException("duplicate Oreslang effect '" + name + "'");
            }
        }
        return new FunctionEffects(set);
    }

    public Set<Effect> effects() { return effects; }
    public boolean contains(Effect effect) { return effects.contains(effect); }

    public FunctionEffects union(FunctionEffects other) {
        EnumSet<Effect> result = EnumSet.noneOf(Effect.class);
        result.addAll(effects);
        result.addAll(other.effects);
        return new FunctionEffects(result);
    }

    /** `with` signatures specify an upper bound, not an exact effect list. */
    public boolean permits(FunctionEffects inferred) {
        return effects.containsAll(inferred.effects);
    }
}
