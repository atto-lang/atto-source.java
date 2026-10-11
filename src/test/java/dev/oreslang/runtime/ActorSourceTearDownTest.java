package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorSourceTearDownTest {
    private static final class State implements ActorRuntime.ActorOwnedGuestState {
        final ActorRuntime.ActorId owner;

        State(ActorRuntime.ActorId owner) {
            this.owner = owner;
        }

        @Override
        public ActorRuntime.ActorId ownerActorId() {
            return owner;
        }
    }

    private static final class Executor implements ActorRuntime.ActorCodeExecutor {
        private final SharedCodeImageStore.CodeImage image;
        private final AtomicInteger receives = new AtomicInteger();
        private final AtomicInteger teardowns = new AtomicInteger();
        private final boolean teardownFails;

        Executor(SharedCodeImageStore.CodeImage image, boolean teardownFails) {
            this.image = image;
            this.teardownFails = teardownFails;
        }

        @Override
        public SharedCodeImageStore.CodeImage codeImage() {
            return image;
        }

        @Override
        public ActorRuntime.ActorOwnedGuestState initializeActor(
                String actorName, ActorRuntime.ActorContext<Object> context) {
            assertEquals("Worker", actorName);
            return new State(context.self().id());
        }

        @Override
        public void receiveActor(
                String actorName,
                ActorRuntime.ActorOwnedGuestState state,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            assertEquals(context.self().id(), state.ownerActorId());
            receives.incrementAndGet();
            if ("self".equals(mail.value())) {
                context.self().stop();
            } else if ("fail".equals(mail.value())) {
                throw new IllegalStateException("receive failure");
            }
        }

        @Override
        public void tearDownActor(
                String actorName,
                ActorRuntime.ActorOwnedGuestState state,
                ActorRuntime.ActorContext<Object> context) {
            assertEquals("Worker", actorName);
            assertEquals(context.self().id(), state.ownerActorId());
            assertTrue(context.currentMail().isEmpty(),
                    "tear_down must not inherit receive envelope authority");
            teardowns.incrementAndGet();
            if (teardownFails) throw new IllegalStateException("teardown failure");
        }
    }

    private static SharedCodeImageStore.CodeImage image(SharedCodeImageStore store) {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        return store.publish("teardown/source.ores", program);
    }

    @Test
    void externalStopInvokesOnceOnlyActorAffineTeardownForAllActorKinds() {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            SharedCodeImageStore.CodeImage codeImage = image(store);
            for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
                Executor executor = new Executor(codeImage, false);
                ActorRuntime.ActorRef<Object> ref =
                        runtime.spawnCodeActor(kind, IsolatePolicy.developer(), executor, "Worker");
                ref.ready().join();
                ref.stop();
                ref.done().join();
                ref.stop();
                assertEquals(0, executor.receives.get());
                assertEquals(1, executor.teardowns.get(), "actor kind " + kind);
                assertFalse(ref.isAlive());
            }
        }
    }

    @Test
    void selfStopRunsTeardownAndRejectsQueuedMessages() {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor executor = new Executor(image(store), false);
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            ref.ready().join();
            ref.send("self");
            ref.done().join();
            assertEquals(1, executor.receives.get());
            assertEquals(1, executor.teardowns.get());
        }
    }

    @Test
    void unhandledFailureDoesNotRunGuestTeardown() {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor executor = new Executor(image(store), false);
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            ref.ready().join();
            ref.send("fail");
            assertThrows(CompletionException.class, () -> ref.done().join());
            assertEquals(0, executor.teardowns.get());
            assertEquals("receive failure", ref.failure().orElseThrow().getMessage());
        }
    }

    @Test
    void teardownFailureStillCompletesPhysicalFinalizationExceptionally() {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor executor = new Executor(image(store), true);
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            ref.ready().join();
            ref.stop();
            CompletionException failure =
                    assertThrows(CompletionException.class, () -> ref.done().join());
            assertEquals("teardown failure", failure.getCause().getMessage());
            assertFalse(ref.isAlive());
            assertEquals(1, executor.teardowns.get());
        }
    }
}
