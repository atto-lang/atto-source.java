package dev.oreslang.types;

import java.util.EnumSet;
import java.util.Set;
import dev.oreslang.types.EffectContracts.Effect;

/** Standalone JDK21 latent-effect oracle; no Maven/JUnit/runtime dependency. */
public final class VerifyLatentEffectRows {
    private VerifyLatentEffectRows() { }
    private static long assertions;

    private static void require(boolean passes, String message) {
        assertions++;
        if (!passes) throw new AssertionError(message);
    }

    private static void rejected(Runnable action, String message) {
        boolean denied = false;
        try { action.run(); } catch (IllegalArgumentException expected) { denied = true; }
        require(denied, message);
    }

    public static void main(String[] args) {
        require(args.length == 0, "standalone checker takes no arguments");
        for (var kind : LatentEffectRows.Kind.values()) {
            var unresolved = LatentEffectRows.unresolved(kind);
            require(unresolved.deferredConstructionEffects().isEmpty(),
                    "deferral must not run latent effects");
            require(unresolved.latentEffects().contains(Effect.UNKNOWN_CALL),
                    "unresolved work must carry unknown effect");
            rejected(() -> unresolved.requireInvocationWithin(Set.of(Effect.UNKNOWN_CALL)),
                    "unknown effect is not a grant");
            rejected(() -> unresolved.requireInvocationWithin(Set.of()),
                    "unresolved cannot claim purity");
        }

        for (int mask = 0; mask <= EffectAbiV1.KNOWN_MASK; mask++) {
            Set<Effect> effects = EffectAbiV1.decode(mask);
            var action = LatentEffectRows.compilerProven(
                    LatentEffectRows.Kind.IO_ACTION, effects, LatentEffectRows.Capture.OWNED);
            require(action.deferredConstructionEffects().isEmpty(),
                    "pure construction of a latent action");
            require(action.requireInvocationWithin(effects).equals(effects),
                    "required effects must be admitted by equal declared upper bound");
            if (!effects.isEmpty()) {
                rejected(() -> action.requireInvocationWithin(Set.of()),
                        "missing effect allowance must deny");
            }
            var future = action.as(LatentEffectRows.Kind.FUTURE);
            var stream = action.as(LatentEffectRows.Kind.STREAM);
            require(future.latentEffects().equals(effects), "future loses effects");
            require(stream.latentEffects().equals(effects), "stream loses effects");
            int otherMask = (mask * 73 + 19) & EffectAbiV1.KNOWN_MASK;
            var callback = LatentEffectRows.compilerProven(
                    LatentEffectRows.Kind.CALLABLE, EffectAbiV1.decode(otherMask),
                    LatentEffectRows.Capture.OWNED);
            EnumSet<Effect> union = EnumSet.noneOf(Effect.class);
            union.addAll(effects);
            union.addAll(callback.latentEffects());
            var chained = action.compose(callback, LatentEffectRows.Kind.IO_ACTION);
            require(chained.latentEffects().equals(union), "bind lost callback effects");
            require(chained.requireInvocationWithin(union).equals(union),
                    "composed row must admit its own effects");
            var contaminated = action.compose(
                    LatentEffectRows.unresolved(LatentEffectRows.Kind.CALLABLE),
                    LatentEffectRows.Kind.IO_ACTION);
            rejected(() -> contaminated.requireInvocationWithin(union),
                    "unknown callback cannot become proved through composition");
        }

        for (var capture : new LatentEffectRows.Capture[]{
                LatentEffectRows.Capture.BORROWED,
                LatentEffectRows.Capture.ACTOR_STATE_LOAN}) {
            var borrowed = LatentEffectRows.compilerProven(LatentEffectRows.Kind.CALLABLE,
                    Set.of(Effect.STATE_READ), capture);
            var owned = LatentEffectRows.compilerProven(LatentEffectRows.Kind.CALLABLE,
                    Set.of(), LatentEffectRows.Capture.OWNED);
            rejected(() -> borrowed.as(LatentEffectRows.Kind.CALLABLE),
                    "non-owned callable cannot be repackaged");
            rejected(() -> borrowed.as(LatentEffectRows.Kind.IO_ACTION),
                    "non-owned callable cannot become deferred IO");
            rejected(() -> borrowed.compose(owned, LatentEffectRows.Kind.CALLABLE),
                    "left non-owned capture cannot escape through bind");
            rejected(() -> owned.compose(borrowed, LatentEffectRows.Kind.STREAM),
                    "right non-owned capture cannot escape through bind");
            rejected(() -> LatentEffectRows.compilerProven(LatentEffectRows.Kind.FUTURE,
                    Set.of(), capture), "future cannot capture actor/borrowed loan");
            rejected(() -> LatentEffectRows.compilerProven(LatentEffectRows.Kind.OBSERVABLE,
                    Set.of(), capture), "observable cannot capture actor/borrowed loan");
        }

        rejected(() -> LatentEffectRows.compilerProven(LatentEffectRows.Kind.CALLABLE,
                Set.of(Effect.UNKNOWN_CALL), LatentEffectRows.Capture.OWNED),
                "compiler must not certify unknown callbacks");
        rejected(() -> LatentEffectRows.compilerProven(LatentEffectRows.Kind.CALLABLE,
                Set.of(Effect.FFI_UNKNOWN), LatentEffectRows.Capture.OWNED),
                "compiler must not certify unknown FFI");
        System.out.println("PASS " + assertions + " latent-effects oracle assertions");
    }
}
