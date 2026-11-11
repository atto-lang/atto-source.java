package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSharedCodeImageWhitelistTest {

    private static final class CodeAwareFactory
            implements ActorRuntime.BehaviorFactory<String> {
        @Override
        public ActorRuntime.Behavior<String> create(
                ActorRuntime.ActorContext<String> context) {
            SharedCodeImageStore.CodeImage image =
                    context.codeImage().orElseThrow(
                            () -> new AssertionError("expected explicit actor code image"));
            assertEquals("app/main.ores", image.codeUnitId());
            return new StopBehavior();
        }
    }

    private static final class NoCodeFactory
            implements ActorRuntime.BehaviorFactory<String> {
        @Override
        public ActorRuntime.Behavior<String> create(
                ActorRuntime.ActorContext<String> context) {
            assertTrue(context.codeImage().isEmpty(),
                    "ordinary actors must not receive ambient code-store access");
            return new StopBehavior();
        }
    }

    private static final class StopBehavior
            implements ActorRuntime.Behavior<String> {
        @Override
        public void onMessage(
                String message,
                ActorRuntime.ActorContext<String> context) {
            context.self().stop();
        }
    }

    @Test
    void oneWhitelistedCodeImageIsSharedByIdentityAcrossActorKinds() throws Exception {
        Ast.Program parsedOnce =
                Parser.parse("pub routine main(): void { return; }");

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            SharedCodeImageStore.CodeImage image =
                    store.publish("app/main.ores", parsedOnce);

            for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
                ActorRuntime.ActorRef<String> ref =
                        runtime.spawnWithCodeImage(
                                kind,
                                IsolatePolicy.developer(),
                                image,
                                new CodeAwareFactory());

                Optional<SharedCodeImageStore.CodeImage> attached =
                        runtime.codeImageFor(ref);
                assertTrue(attached.isPresent(), "actor kind " + kind);
                assertSame(image, attached.orElseThrow(), "actor kind " + kind);
                assertSame(parsedOnce, attached.orElseThrow().program(),
                        "actor kind " + kind);

                ref.send("stop");
                assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS),
                        "actor kind " + kind);
                assertTrue(ref.failure().isEmpty(), "actor kind " + kind);
            }

            assertEquals(1, store.imageCount(),
                    "actors must not publish per-actor code copies");
        }
    }

    @Test
    void ordinaryActorsCannotEnumerateOrSeeUnattachedCodeImages() throws Exception {
        Ast.Program parsedOnce =
                Parser.parse("pub routine main(): void { return; }");

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            store.publish("hidden/main.ores", parsedOnce);

            ActorRuntime.ActorRef<String> ref =
                    runtime.spawnPrivate(new NoCodeFactory());
            assertTrue(runtime.codeImageFor(ref).isEmpty());

            ref.send("stop");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }

    // This remains a host-runtime proof: CodeImage is a shared immutable AST,
    // not a certificate for native executable memory mappings or AOT code.
    private static int advance(int value) {
        return value + 1;
    }

    // Private actor state must be in its authenticated private memory, not in
    // mutable Java behavior fields or a captured host-side map.
    private static final class PrivateCounterBehavior
            implements ActorRuntime.Behavior<String> {
        private final ActorRuntime.PrivateMemoryBlock state;

        private PrivateCounterBehavior(ActorRuntime.PrivateMemoryBlock state) {
            this.state = state;
        }

        @Override
        public void onMessage(String message, ActorRuntime.ActorContext<String> context) {
            if ("inc".equals(message)) {
                int next = advance(Byte.toUnsignedInt(state.readByte(0)));
                state.writeByte(0, (byte) next);
            } else if ("report".equals(message)) {
                context.runtime().emitCurrentActorOutput(
                        Byte.toUnsignedInt(state.readByte(0)));
                context.self().stop();
            } else {
                throw new IllegalArgumentException("unexpected private test mail");
            }
        }
    }

    // The SHARED actor still owns its own behavior instance. Shared execution
    // permissions do not imply global instance fields or automatic sharing.
    private static final class SharedCounterBehavior
            implements ActorRuntime.Behavior<String> {
        private int count;

        @Override
        public void onMessage(String message, ActorRuntime.ActorContext<String> context) {
            if ("inc".equals(message)) {
                count = advance(count);
            } else if ("report".equals(message)) {
                context.runtime().emitCurrentActorOutput(count);
                context.self().stop();
            } else {
                throw new IllegalArgumentException("unexpected shared test mail");
            }
        }
    }

    // Capture-free factory: no CodeImage, map, external mutable reference, or
    // supervisor object is smuggled through factory JVM closure state.
    private static final class IndependentCounterFactory
            implements ActorRuntime.BehaviorFactory<String> {
        @Override
        public ActorRuntime.Behavior<String> create(
                ActorRuntime.ActorContext<String> context) {
            assertEquals("app/shared-code-state.atto",
                    context.codeImage().orElseThrow().codeUnitId());
            if (context.kind() == ActorRuntime.ActorKind.PRIVATE) {
                var state = context.privateMemory().orElseThrow()
                        .allocatePrivateBytes(1);
                state.writeByte(0, (byte) 0);
                return new PrivateCounterBehavior(state);
            }
            assertEquals(ActorRuntime.ActorKind.SHARED, context.kind());
            return new SharedCounterBehavior();
        }
    }

    @Test
    void sharedCodeImageDoesNotShareMutableActorOrSupervisorState() throws Exception {
        Ast.Program parsedOnce =
                Parser.parse("pub routine main(): void { return; }");

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            SharedCodeImageStore.CodeImage image =
                    store.publish("app/shared-code-state.atto", parsedOnce);
            ActorRuntime.BehaviorFactory<String> factory =
                    new IndependentCounterFactory();

            ActorRuntime.ActorRef<String> privateActor =
                    runtime.spawnWithCodeImage(
                            ActorRuntime.ActorKind.PRIVATE,
                            IsolatePolicy.developer(), image, factory);
            ActorRuntime.ActorRef<String> sharedActor =
                    runtime.spawnWithCodeImage(
                            ActorRuntime.ActorKind.SHARED,
                            IsolatePolicy.developer(), image, factory);

            assertSame(image, runtime.codeImageFor(privateActor).orElseThrow());
            assertSame(image, runtime.codeImageFor(sharedActor).orElseThrow());
            assertSame(parsedOnce, image.program());
            assertEquals(1, store.imageCount());

            privateActor.send("inc");
            privateActor.send("inc");
            privateActor.send("inc");
            sharedActor.send("inc");
            privateActor.send("report");
            sharedActor.send("report");

            var privateOutput = privateActor.outputs().next().get(2, TimeUnit.SECONDS);
            var sharedOutput = sharedActor.outputs().next().get(2, TimeUnit.SECONDS);
            assertFalse(privateOutput.done());
            assertFalse(sharedOutput.done());
            assertEquals(3, privateOutput.value().value());
            assertEquals(1, sharedOutput.value().value(),
                    "shared actor kind must not imply shared behavior objects");

            assertTrue(privateActor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(sharedActor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(privateActor.failure().isEmpty());
            assertTrue(sharedActor.failure().isEmpty());

            int supervisorCount = advance(40);
            assertEquals(41, supervisorCount);
            assertEquals(3, privateOutput.value().value());
            assertEquals(1, sharedOutput.value().value());
            assertEquals(1, store.imageCount());
        }
    }
}
