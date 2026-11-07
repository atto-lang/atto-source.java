package dev.oreslang.types;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed compiler admission policy for the two canonical actor state
 * models. This is a staging/type-checking helper, NOT parser or runtime
 * lowering. The compiler must construct this model from a resolved AST and
 * checked ownership/effect summaries; a user-supplied model is not trusted.
 */
public final class ActorStateModeContracts {
    private ActorStateModeContracts() { }

    public enum Mode {
        /** Actor-level 'pure' modifier: reducers are the ONLY state transition. */
        PURE_REDUCER,
        /** Ordinary actor: scoped, sealed self.handle_state access. */
        HANDLE_STATE
    }

    public enum HandlerRole {
        MESSAGE_REDUCER, CONTROL_REDUCER, ORDINARY_HANDLER
    }

    /**
     * Compiler-proven properties, not declaration-level assertions.
     *
     * computedEffects describe operations executed IN the handler. In a
     * reducer, commandEffects describe typed, declarative command values
     * returned as data; their effects occur later under runtime authority.
     */
    public record Handler(
            String qualifiedName,
            HandlerRole role,
            Set<EffectContracts.Effect> computedEffects,
            Set<EffectContracts.Effect> commandEffects,
            boolean suspends,
            boolean accessesActorState,
            boolean mutableAliasEscapes,
            boolean exhaustive) {
        public Handler {
            Objects.requireNonNull(qualifiedName, "qualifiedName");
            Objects.requireNonNull(role, "role");
            if (qualifiedName.isBlank()) throw new IllegalArgumentException("blank handler name");
            computedEffects = Set.copyOf(Objects.requireNonNull(computedEffects, "computedEffects"));
            commandEffects = Set.copyOf(Objects.requireNonNull(commandEffects, "commandEffects"));
        }
    }

    /**
     * actor-level pure must be selected by the declaration, never inferred
     * from a method named reduce/update or a pure helper callable.
     */
    public record ActorModel(Mode mode,
                             List<Handler> handlers,
                             Set<EffectContracts.Effect> commandUpperBound) {
        public ActorModel {
            Objects.requireNonNull(mode, "mode");
            handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers"));
            commandUpperBound = Set.copyOf(Objects.requireNonNull(commandUpperBound, "commandUpperBound"));
        }
    }

    // Only delayed runtime operations can be described as reducer Commands.
    // Compiler exceptional/control effects and ambient mutable access are not
    // command intents. Keep this allowlist narrow until typed lowering exists.
    private static final Set<EffectContracts.Effect> ALLOWED_COMMAND_EFFECTS =
            Set.copyOf(EnumSet.of(
                    EffectContracts.Effect.ACTOR,
                    EffectContracts.Effect.IO,
                    EffectContracts.Effect.EXTERNAL_WRITE,
                    EffectContracts.Effect.ENVIRONMENT,
                    EffectContracts.Effect.CLOCK,
                    EffectContracts.Effect.RANDOM,
                    EffectContracts.Effect.SCHEDULER));

    public static Mode selectMode(boolean actorLevelPureModifier) {
        return actorLevelPureModifier ? Mode.PURE_REDUCER : Mode.HANDLE_STATE;
    }

    /**
     * Validate only the contract facts supplied by earlier compiler passes.
     * Does NOT prove that a handler really is pure, or that dispatch/outbox
     * commit is implemented. AST lowering must call this after effect and
     * ownership inference, then enforce runtime atomicity independently.
     */
    public static void validate(ActorModel actor) {
        Objects.requireNonNull(actor, "actor");
        int messageReducers = 0;
        int controlReducers = 0;
        Set<String> names = new HashSet<>();
        for (Handler handler : actor.handlers()) {
            if (!names.add(handler.qualifiedName())) {
                reject("DUPLICATE_HANDLER", handler.qualifiedName());
            }
            boolean reducer = handler.role() != HandlerRole.ORDINARY_HANDLER;
            if (actor.mode() == Mode.HANDLE_STATE) {
                if (reducer) {
                    reject("REDUCER_REQUIRES_PURE_ACTOR", handler.qualifiedName());
                }
                if (!handler.commandEffects().isEmpty()) {
                    reject("COMMANDS_REQUIRE_REDUCER", handler.qualifiedName());
                }
                continue; // ordinary state loan safety stays in ownership checker
            }
            if (handler.role() == HandlerRole.MESSAGE_REDUCER) messageReducers++;
            if (handler.role() == HandlerRole.CONTROL_REDUCER) controlReducers++;
            if (handler.suspends()) reject("REDUCER_MUST_NOT_SUSPEND", handler.qualifiedName());
            if (handler.accessesActorState()) {
                reject("PURE_ACTOR_MUST_NOT_USE_HANDLE_STATE", handler.qualifiedName());
            }
            if (handler.mutableAliasEscapes()) reject("ALIAS_ESCAPES_PURE_ACTOR", handler.qualifiedName());

            EnumSet<EffectContracts.Effect> forbidden = EnumSet.noneOf(EffectContracts.Effect.class);
            forbidden.addAll(handler.computedEffects());
            forbidden.removeAll(EffectContracts.pureAllowance());
            if (!forbidden.isEmpty()) {
                reject("IMPURE_HANDLER", handler.qualifiedName() + " direct=" + forbidden);
            }
            if (!reducer && !handler.commandEffects().isEmpty()) {
                reject("COMMANDS_REQUIRE_REDUCER", handler.qualifiedName());
            }
            if (reducer && !handler.exhaustive()) {
                reject("NONEXHAUSTIVE_REDUCER", handler.qualifiedName());
            }
            if (handler.commandEffects().contains(EffectContracts.Effect.UNKNOWN_CALL)
                    || handler.commandEffects().contains(EffectContracts.Effect.FFI_UNKNOWN)) {
                reject("UNKNOWN_COMMAND_EFFECT", handler.qualifiedName());
            }
            if (!ALLOWED_COMMAND_EFFECTS.containsAll(handler.commandEffects())) {
                reject("INVALID_COMMAND_EFFECT", handler.qualifiedName()
                        + " only runtime-executable declarative effects are supported");
            }
            if (!actor.commandUpperBound().containsAll(handler.commandEffects())) {
                reject("COMMAND_EXCEEDS_WITH_EFFECTS", handler.qualifiedName());
            }
        }
        if (actor.mode() == Mode.PURE_REDUCER && controlReducers > 1) {
            reject("PURE_ACTOR_HAS_MULTIPLE_CONTROL_REDUCERS",
                    "found " + controlReducers);
        }
        if (actor.mode() == Mode.PURE_REDUCER && messageReducers != 1) {
            reject("PURE_ACTOR_REQUIRES_ONE_MESSAGE_REDUCER",
                    "found " + messageReducers);
        }
    }

    private static void reject(String diagnosticCode, String details) {
        throw new IllegalArgumentException("ORES-ACTOR-" + diagnosticCode + ": " + details);
    }
}
