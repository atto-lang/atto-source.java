package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.nodes.CriticalActorTruffleV1;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.SharedCodeImageStore;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual Atto source -> binder -> typed MIR -> Truffle RootCallTarget, executed
 * on a real ActorRuntime source-code actor's physical mailbox turn.
 *
 * The test executor is a trusted bootstrap adapter mapping a scalar mailbox
 * message to one critical method. It is NOT guest external-mtd lowering.
 */
final class AttoCriticalTruffleActorExecutionTest {
    private static final String SOURCE = """
            use namespace counter;
            declare actor CounterActor;
            define state on actor CounterActor as {
                let int value : 0;
                let bool enabled : true;
            }
            external mtd (CounterActor c) init(): void { return; }
            external mtd (CounterActor c) receive(ActorMail<int> mail): void { return; }

            critical mtd (CounterActor c) add(int delta): int {
                let int answer = 0;
                using state.write(critical |s| -> {
                    if s.enabled then
                        s.value = s.value + delta;
                        answer = s.value;
                    fi
                });
                return answer;
            }
            """;

    private static final class BootstrapExecutor implements ActorRuntime.ActorCodeExecutor {
        private final SharedCodeImageStore.CodeImage image;
        private final CriticalActorTruffleV1.Executable compiled;

        BootstrapExecutor(SharedCodeImageStore.CodeImage image,
                          CriticalActorTruffleV1.Executable compiled) {
            this.image = image;
            this.compiled = compiled;
        }

        @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

        @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                String actorTypeName, ActorRuntime.ActorContext<Object> context) {
            assertEquals("CounterActor", actorTypeName);
            return compiled.initializeActor(context);
        }

        @Override public void receiveActor(
                String actorTypeName,
                ActorRuntime.ActorOwnedGuestState sourceState,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            assertEquals("CounterActor", actorTypeName);
            if (!(mail.value() instanceof Long delta)) {
                throw new SecurityException("only copied i64 data may enter this bootstrap");
            }
            Object result = compiled.invokeFromMailbox(
                    (CriticalActorTruffleV1.OwnedState) sourceState, mail, context, delta);
            // Zero is a test-only snapshot command. It still traverses the
            // same checked Atto critical method, never raw host state.
            if (delta == 0L) {
                context.runtime().emitCurrentActorOutput(result);
                context.self().stop();
            }
        }

        @Override public void tearDownActor(
                String actorTypeName, ActorRuntime.ActorOwnedGuestState sourceState,
                ActorRuntime.ActorContext<Object> context) {
            assertEquals("CounterActor", actorTypeName);
            ((CriticalActorTruffleV1.OwnedState) sourceState).close();
        }
    }

    @Test
    void compiledAttoCounterExecutesTenThousandActorMailboxTurns() throws Exception {
        Ast.Program ast = Parser.parse(SOURCE);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(ast)).getMessage().contains("LOWERING_UNSUPPORTED"),
                "normal guest executable admission must remain closed");

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("src/counter.atto", ast);
            var compiled = CriticalActorTruffleV1.compile(
                    image, "counter@1", "CounterActor", "add");
            var executor = new BootstrapExecutor(image, compiled);
            var actor = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    executor, "CounterActor");
            actor.ready().get(10, TimeUnit.SECONDS);
            for (int i = 0; i < 10_000; i++) {
                // Backpressure is bounded; this awaits admission on HOST ONLY,
                // never while an actor carrier owns a mutable state loan.
                actor.sendAsync(1L).get(10, TimeUnit.SECONDS);
            }
            actor.sendAsync(0L).get(10, TimeUnit.SECONDS);
            var step = actor.outputs().next().get(10, TimeUnit.SECONDS);
            assertFalse(step.done());
            assertEquals(10_000L, step.value().value());
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), "counter actor must remain healthy");
        }
    }

    @Test
    void twoInstancesShareCodeButNeverShareMutableState() throws Exception {
        Ast.Program ast = Parser.parse(SOURCE);
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("src/counter.atto", ast);
            var compiled = CriticalActorTruffleV1.compile(
                    image, "counter@1", "CounterActor", "add");
            var executor = new BootstrapExecutor(image, compiled);
            var one = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    executor, "CounterActor");
            var two = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    executor, "CounterActor");
            one.ready().get(10, TimeUnit.SECONDS);
            two.ready().get(10, TimeUnit.SECONDS);
            for (int i = 0; i < 128; i++) {
                one.sendAsync(1L).get(10, TimeUnit.SECONDS);
                two.sendAsync(2L).get(10, TimeUnit.SECONDS);
            }
            one.sendAsync(0L).get(10, TimeUnit.SECONDS);
            two.sendAsync(0L).get(10, TimeUnit.SECONDS);
            assertEquals(128L, one.outputs().next().get(10, TimeUnit.SECONDS).value().value());
            assertEquals(256L, two.outputs().next().get(10, TimeUnit.SECONDS).value().value());
            assertTrue(one.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(two.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(one.failure().isEmpty());
            assertTrue(two.failure().isEmpty());
            assertEquals(1, store.imageCount(), "only immutable code may be shared");
        }
    }

    @Test
    void directHostCallAndInvalidMethodNameDoNotMintActorAuthority() {
        Ast.Program ast = Parser.parse(SOURCE);
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            var image = store.publish("src/counter.atto", ast);
            var compiled = CriticalActorTruffleV1.compile(
                    image, "counter@1", "CounterActor", "add");
            assertThrows(IllegalArgumentException.class,
                    () -> CriticalActorTruffleV1.compile(
                            image, "counter@1", "CounterActor", "receive"),
                    "private mailbox ingress must never become a critical callable");
            // A forged host context cannot satisfy ActorRuntime current turn.
            ActorRuntime.ActorContext<Object> forged =
                    new ActorRuntime.ActorContext<>() {
                        @Override public ActorRuntime.ActorRef<Object> self() {
                            throw new AssertionError("should not be read after code image validation");
                        }
                        @Override public ActorRuntime runtime() {
                            throw new AssertionError("should not be read after code image validation");
                        }
                        @Override public IsolatePolicy policy() { return IsolatePolicy.developer(); }
                        @Override public ActorRuntime.ActorKind kind() {
                            return ActorRuntime.ActorKind.PRIVATE;
                        }
                        @Override public java.util.Optional<ActorRuntime.ActorMemorySlice> privateMemory() {
                            return java.util.Optional.empty();
                        }
                    };
            assertThrows(SecurityException.class,
                    () -> compiled.initializeActor(forged));
        }
    }
}
