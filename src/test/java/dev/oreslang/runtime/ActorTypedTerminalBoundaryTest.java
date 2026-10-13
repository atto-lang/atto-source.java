package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static dev.oreslang.runtime.ExceptionBoundaryPolicy.Effect.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs actual ActorRuntime carrier/finalization paths. The typed effect is
 * injected at the compiler/ActorCodeExecutor boundary; guest source
 * throw/raise/panic lowering is not implemented by these fixtures.
 */
@Timeout(20)
final class ActorTypedTerminalBoundaryTest {
    private static void assertTerminated(
            ActorRuntime.ActorRef<?> ref,
            ExceptionBoundaryPolicy.Effect origin, long code) throws Exception {
        assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS),
                "actor did not cross finalization barrier");
        assertThrows(ExecutionException.class,
                () -> ref.done().get(5, TimeUnit.SECONDS));
        var notice = ref.terminalFailureNotice().orElseThrow();
        assertEquals(ref.id(), notice.actor());
        assertEquals(ref.kind(), notice.actorKind());
        assertEquals(PANIC, notice.terminal().delivered());
        assertEquals(origin, notice.terminal().origin());
        assertEquals(code, notice.terminal().originCode());
        assertEquals(1, notice.terminal().defaultExitStatus());
        assertTrue(ref.terminalFailureNotice().orElseThrow() == notice,
                "terminal CONTROL metadata must be immutable and published once");
    }

    @Test
    void typedThrowRaiseAndPanicAreNormalizedOnLiveActorFinalization() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var thrown = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw ActorRuntime.GuestEffectSignal.throwing(17);
                    });
            var raised = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw ActorRuntime.GuestEffectSignal.raising(29);
                    });
            var panicked = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw ActorRuntime.GuestEffectSignal.panicking(31);
                    });

            thrown.send("fail");
            raised.send("fail");
            panicked.send("fail");

            assertTerminated(thrown, THROW, 17);
            assertTerminated(raised, RAISE, 29);
            assertTerminated(panicked, PANIC, 31);
            assertInstanceOf(ActorRuntime.GuestEffectSignal.class, thrown.failure().orElseThrow());
            assertInstanceOf(ActorRuntime.GuestEffectSignal.class, raised.failure().orElseThrow());
            assertInstanceOf(ActorRuntime.GuestEffectSignal.class, panicked.failure().orElseThrow());
        }
    }

    @Test
    void compilerCodeActorBridgeRetainsOriginWithoutCrossingMutableGuestState()
            throws Exception {
        Ast.Program parsed = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("app/failure.ores", parsed);
            ActorRuntime.ActorCodeExecutor compilerBridge = new ActorRuntime.ActorCodeExecutor() {
                @Override
                public SharedCodeImageStore.CodeImage codeImage() {
                    return image;
                }

                @Override
                public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String actorType, ActorRuntime.ActorContext<Object> context) {
                    ActorRuntime.ActorId owner = context.self().id();
                    return () -> owner;
                }

                @Override
                public void receiveActor(
                        String actorType, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) {
                    assertEquals(context.self().id(), state.ownerActorId());
                    throw ActorRuntime.GuestEffectSignal.raising(0xffff_ffffL);
                }
            };
            var actor = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE, compilerBridge, "Worker");
            actor.send("raise");
            assertTerminated(actor, RAISE, 0xffff_ffffL);
            var record = actor.terminalFailureNotice().orElseThrow();
            assertEquals(Optional.empty(), record.parent());
            assertEquals(ActorRuntime.GuestEffectSignal.class.getName(),
                    record.diagnosticType());
        }
    }

    @Test
    void arbitraryHostExceptionsAreNotFabricatedAsGuestThrow() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw new IllegalStateException("host-side failure");
                    });
            actor.send("fail");
            assertTerminated(actor, PANIC, 0);
            assertEquals(IllegalStateException.class.getName(),
                    actor.terminalFailureNotice().orElseThrow().diagnosticType());
        }
    }

    @Test
    void structuredCancellationRetainsSeparateTerminalCategory() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> {});
            assertTrue(actor.cancel());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> actor.done().get(5, TimeUnit.SECONDS));
            var notice = actor.terminalFailureNotice().orElseThrow();
            assertEquals(CANCEL, notice.terminal().delivered());
            assertEquals(CANCEL, notice.terminal().origin());
            assertFalse(notice.terminal().delivered() == PANIC);
        }
    }

    @Test
    void gracefulStopHasNoFailureNotice() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> context.self().stop());
            actor.send("finish");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(actor.done().get(5, TimeUnit.SECONDS));
            assertTrue(actor.terminalFailureNotice().isEmpty());
        }
    }

    @Test
    void compilerEffectSignalRejectsOutOfRangeCodes() {
        assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.GuestEffectSignal.throwing(-1));
        assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.GuestEffectSignal.raising(0x1_0000_0000L));
    }
}
