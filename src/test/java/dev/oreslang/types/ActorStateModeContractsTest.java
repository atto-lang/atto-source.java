package dev.oreslang.types;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.oreslang.types.ActorStateModeContracts.ActorModel;
import dev.oreslang.types.ActorStateModeContracts.Handler;
import dev.oreslang.types.ActorStateModeContracts.HandlerRole;
import dev.oreslang.types.ActorStateModeContracts.Mode;
import dev.oreslang.types.EffectContracts.Effect;

/** Contract tests for an actor-level pure reducer selector, not source syntax. */
final class ActorStateModeContractsTest {
    private static Handler messageReducer(Set<Effect> computed, Set<Effect> commands) {
        return new Handler("Counter.reduce(CountMail)", HandlerRole.MESSAGE_REDUCER,
                computed, commands, false, false, false, true);
    }

    private static ActorModel pure(Handler... handlers) {
        return new ActorModel(Mode.PURE_REDUCER, List.of(handlers),
                Set.of(Effect.ACTOR, Effect.IO));
    }

    private static void rejects(String code, ActorModel model) {
        IllegalArgumentException problem =
                assertThrows(IllegalArgumentException.class,
                        () -> ActorStateModeContracts.validate(model));
        assertTrue(problem.getMessage().contains("ORES-ACTOR-" + code),
                problem.getMessage());
    }

    @Test
    void classModifierAloneSelectsReducerMode() {
        assertEquals(Mode.PURE_REDUCER, ActorStateModeContracts.selectMode(true));
        assertEquals(Mode.HANDLE_STATE, ActorStateModeContracts.selectMode(false));

        assertDoesNotThrow(() -> ActorStateModeContracts.validate(
                pure(messageReducer(Set.of(), Set.of(Effect.ACTOR)))));
        assertDoesNotThrow(() -> ActorStateModeContracts.validate(
                new ActorModel(Mode.HANDLE_STATE,
                        List.of(new Handler("Counter.receive", HandlerRole.ORDINARY_HANDLER,
                                Set.of(Effect.ACTOR), Set.of(), true, true, false, false)),
                        Set.of())));
    }

    @Test
    void nonPureActorNeverReceivesImplicitReducerSemantics() {
        rejects("REDUCER_REQUIRES_PURE_ACTOR",
                new ActorModel(Mode.HANDLE_STATE,
                        List.of(messageReducer(Set.of(), Set.of())),
                        Set.of()));
        rejects("COMMANDS_REQUIRE_REDUCER",
                new ActorModel(Mode.HANDLE_STATE,
                        List.of(new Handler("Worker.receive", HandlerRole.ORDINARY_HANDLER,
                                Set.of(), Set.of(Effect.ACTOR), false, true, false, true)),
                        Set.of()));
        assertDoesNotThrow(() -> ActorStateModeContracts.validate(
                new ActorModel(Mode.HANDLE_STATE, List.of(), Set.of())));
    }

    @Test
    void pureActorRequiresOneExhaustiveMessageReducer() {
        rejects("PURE_ACTOR_REQUIRES_ONE_MESSAGE_REDUCER", pure());
        rejects("PURE_ACTOR_REQUIRES_ONE_MESSAGE_REDUCER",
                pure(messageReducer(Set.of(), Set.of()),
                        new Handler("Counter.reduceOther", HandlerRole.MESSAGE_REDUCER,
                                Set.of(), Set.of(), false, false, false, true)));
        rejects("NONEXHAUSTIVE_REDUCER",
                pure(new Handler("Counter.reduce", HandlerRole.MESSAGE_REDUCER,
                        Set.of(), Set.of(), false, false, false, false)));
        rejects("DUPLICATE_HANDLER",
                pure(messageReducer(Set.of(), Set.of()),
                        messageReducer(Set.of(), Set.of())));
    }

    @Test
    void directActorStateAccessAndSuspensionAreForbiddenForPureActor() {
        rejects("PURE_ACTOR_MUST_NOT_USE_HANDLE_STATE",
                pure(new Handler("Counter.reduce", HandlerRole.MESSAGE_REDUCER,
                        Set.of(), Set.of(), false, true, false, true)));
        rejects("REDUCER_MUST_NOT_SUSPEND",
                pure(new Handler("Counter.reduce", HandlerRole.MESSAGE_REDUCER,
                        Set.of(), Set.of(), true, false, false, true)));
        rejects("ALIAS_ESCAPES_PURE_ACTOR",
                pure(new Handler("Counter.reduce", HandlerRole.MESSAGE_REDUCER,
                        Set.of(), Set.of(), false, false, true, true)));
    }

    @Test
    void pureActorCannotExecuteEffectsOrUnverifiedFfiDirectly() {
        for (Effect effect : List.of(Effect.ACTOR, Effect.IO, Effect.SCHEDULER,
                Effect.AMBIENT_READ, Effect.FFI_UNKNOWN, Effect.UNKNOWN_CALL,
                Effect.EXTERNAL_WRITE, Effect.SYNCHRONIZATION)) {
            rejects("IMPURE_HANDLER", pure(messageReducer(Set.of(effect), Set.of())));
        }
        // Pure does not assert no-panic/no-throw, by existing effect contract.
        assertDoesNotThrow(() -> ActorStateModeContracts.validate(
                pure(messageReducer(Set.of(Effect.THROW, Effect.PANIC), Set.of()))));
    }

    @Test
    void declarativeCommandsUseWithEffectBoundsButAreNotDirectEffects() {
        assertDoesNotThrow(() -> ActorStateModeContracts.validate(
                pure(messageReducer(Set.of(), Set.of(Effect.ACTOR, Effect.IO)))));
        rejects("COMMAND_EXCEEDS_WITH_EFFECTS",
                new ActorModel(Mode.PURE_REDUCER,
                        List.of(messageReducer(Set.of(), Set.of(Effect.IO))),
                        Set.of(Effect.ACTOR)));
        rejects("UNKNOWN_COMMAND_EFFECT",
                pure(messageReducer(Set.of(), Set.of(Effect.FFI_UNKNOWN))));
        rejects("UNKNOWN_COMMAND_EFFECT",
                pure(messageReducer(Set.of(), Set.of(Effect.UNKNOWN_CALL))));
        rejects("COMMANDS_REQUIRE_REDUCER",
                pure(messageReducer(Set.of(), Set.of()),
                    new Handler("Counter.helper", HandlerRole.ORDINARY_HANDLER,
                            Set.of(), Set.of(Effect.ACTOR), false, false, false, true)));
    }

    @Test
    void pureControlReducerIsValidatedButPreservesSingleMessageReducer() {
        var control = new Handler("Counter.onControl", HandlerRole.CONTROL_REDUCER,
                Set.of(), Set.of(Effect.ACTOR), false, false, false, true);
        assertDoesNotThrow(() -> ActorStateModeContracts.validate(
                pure(messageReducer(Set.of(), Set.of()), control)));
        rejects("IMPURE_HANDLER",
                pure(messageReducer(Set.of(), Set.of()),
                    new Handler("Counter.onControl", HandlerRole.CONTROL_REDUCER,
                            Set.of(Effect.IO), Set.of(), false, false, false, true)));
        rejects("PURE_ACTOR_HAS_MULTIPLE_CONTROL_REDUCERS",
                pure(messageReducer(Set.of(), Set.of()), control,
                    new Handler("Counter.onControl2", HandlerRole.CONTROL_REDUCER,
                            Set.of(), Set.of(), false, false, false, true)));
    }
}
