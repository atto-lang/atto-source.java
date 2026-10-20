package dev.oreslang.types;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import dev.oreslang.types.EffectContracts.Effect;

/**
 * Static compiler descriptor for *deferred* computations (Haskell-style IO,
 * callable values, Futures, streams and observables). No action is executed
 * here, and neither a declared effect bound nor metadata mints runtime
 * capability authority.
 *
 * The constructor is sealed. Producers outside the compiler effects package
 * receive UNKNOWN_CALL, never a forged pure effect row.
 */
public final class LatentEffectRows {
    private LatentEffectRows() { }

    public enum Kind { CALLABLE, IO_ACTION, FUTURE, STREAM, OBSERVABLE }

    public enum Capture { OWNED, BORROWED, ACTOR_STATE_LOAN }

    public static final class Descriptor {
        private final Kind kind;
        private final Set<Effect> latent;
        private final Capture capture;
        private final boolean compilerProven;

        private Descriptor(Kind kind, Set<Effect> latent, Capture capture, boolean compilerProven) {
            this.kind = Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(latent, "latent effects");
            this.capture = Objects.requireNonNull(capture, "capture");
            if (kind != Kind.CALLABLE && capture != Capture.OWNED) {
                throw new IllegalArgumentException("non-owned capture in deferred operation");
            }
            EnumSet<Effect> safe = EnumSet.noneOf(Effect.class);
            safe.addAll(latent);
            // A missing dynamic target is never a certificate of no effects.
            if (!compilerProven) safe.add(Effect.UNKNOWN_CALL);
            this.latent = Collections.unmodifiableSet(safe);
            this.compilerProven = compilerProven;
        }

        public Kind kind() { return kind; }
        public Set<Effect> latentEffects() { return latent; }
        public Capture capture() { return capture; }
        public boolean compilerProven() { return compilerProven; }

        /**
         * Constructing a deferred action does NOT execute its effects.
         * This does not include allocator/safety effects of an actual runtime
         * object constructor, which belong to its separate operation.
         */
        public Set<Effect> deferredConstructionEffects() {
            return Set.of();
        }

        /**
         * This is a static signature compatibility check only. Actual
         * execution must still pass runtime capability/actor-loan admission.
         */
        public Set<Effect> requireInvocationWithin(Set<Effect> allowed) {
            Objects.requireNonNull(allowed, "allowed");
            if (!compilerProven || latent.contains(Effect.UNKNOWN_CALL)
                    || latent.contains(Effect.FFI_UNKNOWN)
                    || allowed.contains(Effect.UNKNOWN_CALL)
                    || allowed.contains(Effect.FFI_UNKNOWN)) {
                throw new IllegalArgumentException("unverified latent effect row");
            }
            if (!allowed.containsAll(latent)) {
                EnumSet<Effect> excess = EnumSet.noneOf(Effect.class);
                excess.addAll(latent);
                excess.removeAll(allowed);
                throw new IllegalArgumentException("latent effects exceed declared with row: " + excess);
            }
            if (capture != Capture.OWNED
                    && (kind != Kind.CALLABLE || latent.contains(Effect.SUSPEND)
                            || latent.contains(Effect.SCHEDULER))) {
                throw new IllegalArgumentException(
                        "borrowed/state loan cannot escape into deferred work or suspension");
            }
            return latent;
        }

        /** map/bind/flatMap preserve both callback and original latent effects. */
        public Descriptor compose(Descriptor next, Kind resultKind) {
            Objects.requireNonNull(next, "next");
            if (capture != Capture.OWNED || next.capture != Capture.OWNED) {
                throw new IllegalArgumentException("deferred composition needs owned captures");
            }
            EnumSet<Effect> union = EnumSet.noneOf(Effect.class);
            union.addAll(latent);
            union.addAll(next.latent);
            Capture merged = (capture == Capture.ACTOR_STATE_LOAN
                    || next.capture == Capture.ACTOR_STATE_LOAN)
                    ? Capture.ACTOR_STATE_LOAN
                    : (capture == Capture.BORROWED || next.capture == Capture.BORROWED)
                        ? Capture.BORROWED : Capture.OWNED;
            return new Descriptor(resultKind, union, merged,
                    compilerProven && next.compilerProven);
        }

        /** Change the deferred container without discarding latent effects. */
        public Descriptor as(Kind resultKind) {
            if (capture != Capture.OWNED) {
                throw new IllegalArgumentException("repackaging non-owned callable is forbidden");
            }
            return new Descriptor(resultKind, latent, capture, compilerProven);
        }
    }

    /** Public construction never certifies a pure callable. */
    public static Descriptor unresolved(Kind kind) {
        return new Descriptor(kind, Set.of(Effect.UNKNOWN_CALL), Capture.OWNED, false);
    }

    /**
     * Only compiler-internal elaboration may provide verified effect facts.
     * This model must not be mistaken for authenticated guest/runtime ABI.
     */
    static Descriptor compilerProven(Kind kind, Set<Effect> effects, Capture capture) {
        if (effects.contains(Effect.UNKNOWN_CALL) || effects.contains(Effect.FFI_UNKNOWN)) {
            throw new IllegalArgumentException("unknown effects cannot be certified");
        }
        return new Descriptor(kind, effects, capture, true);
    }
}
