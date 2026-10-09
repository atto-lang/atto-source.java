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

    private static final class BlockingSharedCodeFactory
            implements ActorRuntime.BehaviorFactory<String> {
        private final SharedCodeImageStore.CodeImage expected;
        private final java.util.concurrent.CountDownLatch arrived;
        private final java.util.concurrent.CountDownLatch released;
        private final java.util.concurrent.atomic.AtomicInteger active;
        private final java.util.concurrent.atomic.AtomicInteger maximum;

        BlockingSharedCodeFactory(
                SharedCodeImageStore.CodeImage expected,
                java.util.concurrent.CountDownLatch arrived,
                java.util.concurrent.CountDownLatch released,
                java.util.concurrent.atomic.AtomicInteger active,
                java.util.concurrent.atomic.AtomicInteger maximum) {
            this.expected = expected;
            this.arrived = arrived;
            this.released = released;
            this.active = active;
            this.maximum = maximum;
        }

        @Override
        public ActorRuntime.Behavior<String> create(ActorRuntime.ActorContext<String> context) {
            assertSame(expected, context.codeImage().orElseThrow());
            return (message, turn) -> {
                int now = active.incrementAndGet();
                maximum.accumulateAndGet(now, Math::max);
                arrived.countDown();
                try {
                    if (!released.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("shared actor parallelism test timed out");
                    }
                } finally {
                    active.decrementAndGet();
                }
                turn.self().stop();
            };
        }
    }

    @Test
    void distinctSharedActorsExecuteConcurrentlyWithOneImmutableCodeImage()
            throws Exception {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("app/main.ores", program);
            var arrived = new java.util.concurrent.CountDownLatch(2);
            var released = new java.util.concurrent.CountDownLatch(1);
            var active = new java.util.concurrent.atomic.AtomicInteger();
            var maximum = new java.util.concurrent.atomic.AtomicInteger();
            var behavior = new BlockingSharedCodeFactory(
                    image, arrived, released, active, maximum);
            var a = runtime.spawnWithCodeImage(ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(), image, behavior);
            var b = runtime.spawnWithCodeImage(ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(), image, behavior);
            try {
                assertSame(image, runtime.codeImageFor(a).orElseThrow());
                assertSame(image, runtime.codeImageFor(b).orElseThrow());
                a.send("run");
                b.send("run");
                assertTrue(arrived.await(5, TimeUnit.SECONDS),
                        "independent SHARED actors should run on separate carriers");
            } finally {
                released.countDown();
            }
            assertTrue(a.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, maximum.get(),
                    "do not introduce one global guest lock across all SHARED actors");
            assertEquals(1, store.imageCount(),
                    "both actors must share the immutable AST/program image");
        }
    }
}
