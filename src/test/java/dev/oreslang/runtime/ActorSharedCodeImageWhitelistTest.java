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

    // This is a Java host-runtime test: the code image is a shared parsed AST,
    // not evidence that an AttoLang AOT binary has shared executable pages.
    private static int advance(int value) {
        return value + 1;
    }

    @Test
    void sharedCodeImageDoesNotShareMutableActorOrSupervisorState() throws Exception {
        Ast.Program parsedOnce =
                Parser.parse("pub routine main(): void { return; }");
        java.util.concurrent.ConcurrentHashMap<String, Integer> snapshots =
                new java.util.concurrent.ConcurrentHashMap<>();

        // Each actor gets a new mutable behavior instance. The callback code
        // and the attached immutable code image remain shared by reference.
        final class CounterBehavior implements ActorRuntime.Behavior<String> {
            private int count;

            @Override
            public void onMessage(String message,
                    ActorRuntime.ActorContext<String> context) {
                if ("inc".equals(message)) {
                    count = advance(count);
                } else if (message.startsWith("report:")) {
                    snapshots.put(message.substring("report:".length()), count);
                    context.self().stop();
                } else {
                    throw new IllegalArgumentException("unexpected test mail");
                }
            }
        }

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            SharedCodeImageStore.CodeImage image =
                    store.publish("app/shared-code-state.atto", parsedOnce);
            ActorRuntime.BehaviorFactory<String> factory = context -> {
                assertSame(image, context.codeImage().orElseThrow());
                return new CounterBehavior();
            };

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
            privateActor.send("report:private");
            sharedActor.send("report:shared");

            assertTrue(privateActor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(sharedActor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(privateActor.failure().isEmpty());
            assertTrue(sharedActor.failure().isEmpty());
            assertEquals(3, snapshots.get("private"));
            assertEquals(1, snapshots.get("shared"),
                    "shared actor kind must not imply shared behavior objects");

            // The supervisor/root can execute the same code body on its own
            // state, but this cannot mutate an actor's independent state.
            int supervisorCount = advance(40);
            assertEquals(41, supervisorCount);
            assertEquals(3, snapshots.get("private"));
            assertEquals(1, snapshots.get("shared"));
        }
    }
}
