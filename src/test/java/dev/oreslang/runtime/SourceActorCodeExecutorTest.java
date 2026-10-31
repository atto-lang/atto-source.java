package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class SourceActorCodeExecutorTest {

    private static final class State implements ActorRuntime.ActorOwnedGuestState {
        private final ActorRuntime.ActorId owner;
        private int messages;

        private State(ActorRuntime.ActorId owner) {
            this.owner = owner;
        }

        @Override
        public ActorRuntime.ActorId ownerActorId() {
            return owner;
        }
    }

    private record Executor(SharedCodeImageStore.CodeImage codeImage)
            implements ActorRuntime.ActorCodeExecutor {

        @Override
        public ActorRuntime.ActorOwnedGuestState initializeActor(
                String actorTypeName,
                ActorRuntime.ActorContext<Object> context) {
            assertEquals("Worker", actorTypeName);
            assertSame(codeImage, context.codeImage().orElseThrow());
            assertTrue(context.currentMail().isEmpty(),
                    "startup must execute before any mailbox message");
            return new State(context.self().id());
        }

        @Override
        public void receiveActor(
                String actorTypeName,
                ActorRuntime.ActorOwnedGuestState rawState,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            assertEquals("Worker", actorTypeName);
            State state = assertInstanceOf(State.class, rawState);
            assertEquals(context.self().id(), state.ownerActorId());
            assertEquals(context.self().id(), mail.recipient());
            assertEquals(0L, mail.sequence());
            assertEquals("stop", mail.value());
            assertEquals(mail, context.currentMail().orElseThrow());
            state.messages++;
            assertEquals(1, state.messages);
            context.self().stop();
        }
    }

    @Test
    void sourceActorStateIsCreatedOnActorDomainAndReceivesTypedEnvelope()
            throws Exception {
        Ast.Program program =
                Parser.parse("pub routine main(): void { return; }");

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            SharedCodeImageStore.CodeImage image =
                    store.publish("app/main.ores", program);
            Executor executor = new Executor(image);

            for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
                ActorRuntime.ActorRef<Object> ref =
                        runtime.spawnCodeActor(
                                kind,
                                IsolatePolicy.developer(),
                                executor,
                                "Worker");

                assertSame(image, runtime.codeImageFor(ref).orElseThrow());
                assertNull(ref.ready().join());
                assertTrue(ref.isAlive());

                ref.send("stop");
                assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS),
                        "actor kind " + kind);
                assertTrue(ref.failure().isEmpty(), "actor kind " + kind);
                assertNull(ref.done().join());
            }

            assertEquals(1, store.imageCount(),
                    "source actors must reuse the exact shared code image");
        }
    }

    @Test
    void sourceActorReadyFailsWhenInitializedStateClaimsWrongOwner()
            throws Exception {
        Ast.Program program =
                Parser.parse("pub routine main(): void { return; }");

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            SharedCodeImageStore.CodeImage image =
                    store.publish("app/main.ores", program);

            ActorRuntime.ActorCodeExecutor bad =
                    new ActorRuntime.ActorCodeExecutor() {
                        @Override
                        public SharedCodeImageStore.CodeImage codeImage() {
                            return image;
                        }

                        @Override
                        public ActorRuntime.ActorOwnedGuestState initializeActor(
                                String actorTypeName,
                                ActorRuntime.ActorContext<Object> context) {
                            ActorRuntime.ActorId wrong =
                                    ActorRuntime.ActorId.create();
                            return () -> wrong;
                        }

                        @Override
                        public void receiveActor(
                                String actorTypeName,
                                ActorRuntime.ActorOwnedGuestState state,
                                ActorRuntime.ActorInboxMail<Object> mail,
                                ActorRuntime.ActorContext<Object> context) {
                            fail("mailbox handler must not run after startup ownership failure");
                        }
                    };

            ActorRuntime.ActorRef<Object> ref =
                    runtime.spawnCodeActor(
                            ActorRuntime.ActorKind.PRIVATE,
                            bad,
                            "Worker");

            Throwable failure = assertThrows(
                    RuntimeException.class,
                    () -> ref.ready().join());
            assertTrue(failure.getMessage().contains("does not match actor")
                            || (failure.getCause() != null
                                && failure.getCause().getMessage().contains(
                                        "does not match actor")),
                    failure.toString());
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isPresent());
        }
    }

    @Test
    void typedRuntimeStartupBridgeCopiesBoundedScalarArgumentsBeforeActorAdmission()
            throws Exception {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            SharedCodeImageStore.CodeImage image = store.publish("app/init.ores", program);
            java.util.concurrent.atomic.AtomicReference<java.util.List<Object>> received =
                    new java.util.concurrent.atomic.AtomicReference<>();
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context) {
                    fail("legacy zero-argument initializer cannot discard startup inputs");
                    return null;
                }
                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context,
                        java.util.List<Object> inputs) {
                    assertEquals("Worker", type);
                    assertTrue(context.currentMail().isEmpty());
                    assertSame(image, context.codeImage().orElseThrow());
                    received.set(inputs);
                    return new State(context.self().id());
                }
                @Override public void receiveActor(
                        String type, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) {
                    context.self().stop();
                }
            };
            java.util.ArrayList<Object> original = new java.util.ArrayList<>(
                    java.util.List.of(12, true, "tenant-a", new String(Character.toChars(0x1F999))));
            ActorRuntime.ActorRef<Object> ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE, executor, "Worker", original);
            original.set(0, 99L);
            original.set(2, "tenant-b");
            assertNull(ref.ready().join());
            assertEquals(java.util.List.of(12L, true, "tenant-a", new String(Character.toChars(0x1F999))), received.get());
            assertInstanceOf(Long.class, received.get().getFirst(),
                    "source int values must have one canonical int64 representation");
            assertThrows(UnsupportedOperationException.class,
                    () -> received.get().set(0, 99L));
            ref.send("stop");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void startupBridgeRejectsMutableOrOversizedArgumentsBeforeCreatingActor()
            throws Exception {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor executor = new Executor(store.publish("app/init-denial.ores", program));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                            executor, "Worker", java.util.List.of(new Object())));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                            executor, "Worker", java.util.Arrays.asList((Object) null)));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                            executor, "Worker", java.util.Collections.nCopies(33, 1L)));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                            executor, "Worker", java.util.List.of("x".repeat(4097))));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                            executor, "Worker", java.util.List.of("€".repeat(1366))));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                            executor, "Worker", java.util.List.of(Double.NaN)));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                            executor, "Worker", java.util.List.of(Double.POSITIVE_INFINITY)));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                            executor, "Worker",
                            java.util.List.of(Character.toString((char) 0xD800))));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                            executor, "Worker",
                            java.util.List.of(Character.toString((char) 0xDC00))));

            // A typed ActorRef is not yet a transferable capability for
            // initialization. Deny it even when the reference belongs to
            // the very same ActorRuntime and its owner is currently live.
            ActorRuntime.ActorRef<Object> dependency = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE, executor, "Worker");
            assertNull(dependency.ready().join());
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                            executor, "Worker", java.util.List.of(dependency)));
            dependency.send("stop");
            assertTrue(dependency.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void oldSourceExecutorFailsReadyForUnexpectedNonemptyStartupInputs()
            throws Exception {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Executor oldExecutor = new Executor(store.publish("app/old.ores", program));
            ActorRuntime.ActorRef<Object> ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE, oldExecutor, "Worker",
                    java.util.List.of(99L));
            Throwable failure = assertThrows(RuntimeException.class,
                    () -> ref.ready().join());
            assertTrue(failure.toString().contains("initialized argument ABI")
                    || (failure.getCause() != null
                       && failure.getCause().toString().contains("initialized argument ABI")),
                    failure.toString());
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isPresent());
        }
    }
}
