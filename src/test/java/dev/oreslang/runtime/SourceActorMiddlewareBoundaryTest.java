package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Proves the scheduler-owned pre-dispatch boundary, not guest decorators. */
final class SourceActorMiddlewareBoundaryTest {

    private static final class OwnedState implements ActorRuntime.ActorOwnedGuestState {
        private final ActorRuntime.ActorId owner;
        private OwnedState(ActorRuntime.ActorId owner) { this.owner = owner; }
        @Override public ActorRuntime.ActorId ownerActorId() { return owner; }
    }

    private static SharedCodeImageStore.CodeImage image(SharedCodeImageStore store) {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        return store.publish("middleware-boundary.ores", program);
    }

    @Test
    void middlewareRunsWithinActorTurnAndNormalizesOnlyTheValue() throws Exception {
        for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
            try (SharedCodeImageStore store = new SharedCodeImageStore();
                 ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
                var image = image(store);
                var entered = new AtomicInteger();
                var received = new AtomicInteger();
                var seen = new AtomicReference<String>();
                ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                    @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
                    @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                            String type, ActorRuntime.ActorContext<Object> context) {
                        return new OwnedState(context.self().id());
                    }
                    @Override public ActorRuntime.ActorInboxMail<Object> prepareActorMail(
                            String type, ActorRuntime.ActorOwnedGuestState state,
                            ActorRuntime.ActorInboxMail<Object> mail,
                            ActorRuntime.ActorContext<Object> context) {
                        entered.incrementAndGet();
                        assertEquals("Worker", type);
                        assertEquals(context.self().id(), state.ownerActorId());
                        assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                        assertEquals(mail, context.currentMail().orElseThrow());
                        assertEquals("  ping  ", mail.value());
                        return new ActorRuntime.ActorInboxMail<>(
                                mail.recipient(), mail.sender(), mail.sequence(),
                                ((String) mail.value()).strip());
                    }
                    @Override public void receiveActor(String type,
                            ActorRuntime.ActorOwnedGuestState state,
                            ActorRuntime.ActorInboxMail<Object> mail,
                            ActorRuntime.ActorContext<Object> context) {
                        received.incrementAndGet();
                        seen.set((String) mail.value());
                        assertEquals("ping", context.currentMail().orElseThrow().value());
                        assertEquals(mail, context.currentMail().orElseThrow());
                        context.self().stop();
                    }
                };
                var worker = runtime.spawnCodeActor(kind, IsolatePolicy.developer(), executor, "Worker");
                worker.ready().join();
                worker.send("  ping  ");
                assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(worker.failure().isEmpty(), "unexpected actor failure: " + worker.failure());
                assertEquals("ping", seen.get());
                assertEquals(1, entered.get());
                assertEquals(1, received.get());
            }
        }
    }

    @Test
    void middlewareCannotForgeMailboxSequenceOrSenderAndHandlerNeverRuns() throws Exception {
        for (boolean forgeSender : new boolean[]{false, true}) {
            try (SharedCodeImageStore store = new SharedCodeImageStore();
                 ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
                var image = image(store);
                var called = new AtomicInteger();
                ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                    @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
                    @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                            String type, ActorRuntime.ActorContext<Object> context) {
                        return new OwnedState(context.self().id());
                    }
                    @Override public ActorRuntime.ActorInboxMail<Object> prepareActorMail(
                            String type, ActorRuntime.ActorOwnedGuestState state,
                            ActorRuntime.ActorInboxMail<Object> mail,
                            ActorRuntime.ActorContext<Object> context) {
                        return new ActorRuntime.ActorInboxMail<>(
                                mail.recipient(),
                                forgeSender ? java.util.Optional.of(ActorRuntime.ActorId.create()) : mail.sender(),
                                forgeSender ? mail.sequence() : mail.sequence() + 1,
                                mail.value());
                    }
                    @Override public void receiveActor(
                            String type, ActorRuntime.ActorOwnedGuestState state,
                            ActorRuntime.ActorInboxMail<Object> mail,
                            ActorRuntime.ActorContext<Object> context) {
                        called.incrementAndGet();
                    }
                };
                var worker = runtime.spawnCodeActor(
                        ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                        executor, "Worker");
                worker.ready().join();
                worker.send("ping");
                assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(worker.failure().isPresent());
                assertEquals(0, called.get());
            }
        }
    }

    @Test
    void mutableReplacementCannotEscapeHostIntoActorWithoutOwnershipTransfer() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = image(store);
            var calls = new AtomicInteger();
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context) {
                    return new OwnedState(context.self().id());
                }
                @Override public ActorRuntime.ActorInboxMail<Object> prepareActorMail(
                        String type, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) {
                    return new ActorRuntime.ActorInboxMail<>(
                            mail.recipient(), mail.sender(), mail.sequence(),
                            new java.util.ArrayList<>(java.util.List.of("mutable")));
                }
                @Override public void receiveActor(
                        String type, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) {
                    calls.incrementAndGet();
                }
            };
            var worker = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    executor, "Worker");
            worker.ready().join();
            worker.send("ping");
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0, calls.get());
            var failure = worker.failure().orElseThrow();
            assertTrue(failure.toString().contains("immutable scalar"), failure.toString());
        }
    }

    @Test
    void scalarMiddlewareCannotExpandBeyondOriginalMailboxBudget() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = image(store);
            var calls = new AtomicInteger();
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context) {
                    return new OwnedState(context.self().id());
                }
                @Override public ActorRuntime.ActorInboxMail<Object> prepareActorMail(
                        String type, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) {
                    return new ActorRuntime.ActorInboxMail<>(
                            mail.recipient(), mail.sender(), mail.sequence(),
                            "x".repeat(10000));
                }
                @Override public void receiveActor(
                        String type, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) {
                    calls.incrementAndGet();
                }
            };
            var worker = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(), executor, "Worker");
            worker.ready().join();
            worker.send("x");
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0, calls.get());
            var failure = worker.failure().orElseThrow();
            assertTrue(failure.toString().contains("mailbox reservation"), failure.toString());
        }
    }

    @Test
    void nullMiddlewareEnvelopeIsFatalRatherThanSilentlySkippingHandler() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = image(store);
            var calls = new AtomicInteger();
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context) {
                    return new OwnedState(context.self().id());
                }
                @Override public ActorRuntime.ActorInboxMail<Object> prepareActorMail(
                        String type, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) {
                    return null;
                }
                @Override public void receiveActor(
                        String type, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) {
                    calls.incrementAndGet();
                }
            };
            var worker = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(), executor, "Worker");
            worker.ready().join();
            worker.send("ping");
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(worker.failure().isPresent());
            assertEquals(0, calls.get());
        }
    }
}
