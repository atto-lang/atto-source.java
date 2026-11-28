package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorOutputStreamTest {

    private static final class MutableOutputBox {
        int value = 1;
    }

    @Test
    void privateAndSharedActorsPublishCopiedFrozenOrderedData() {
        for (ActorRuntime.ActorKind kind :
                List.of(ActorRuntime.ActorKind.PRIVATE, ActorRuntime.ActorKind.SHARED)) {
            try (ActorRuntime runtime = new ActorRuntime()) {
                ActorRuntime.ActorRef<String> ref = switch (kind) {
                    case PRIVATE -> runtime.spawnPrivate(
                            context -> (message, turn) -> {
                                ArrayList<String> mutable = new ArrayList<>();
                                mutable.add("one");
                                turn.runtime().emitCurrentActorOutput(mutable);
                                mutable.add("mutated-after-send");
                                turn.runtime().emitCurrentActorOutput("two");
                                turn.runtime().endCurrentActor();
                            });
                    case SHARED -> runtime.spawnShared(
                            context -> (message, turn) -> {
                                ArrayList<String> mutable = new ArrayList<>();
                                mutable.add("one");
                                turn.runtime().emitCurrentActorOutput(mutable);
                                mutable.add("mutated-after-send");
                                turn.runtime().emitCurrentActorOutput("two");
                                turn.runtime().endCurrentActor();
                            });
                    case UNTRUSTED -> throw new AssertionError("not part of this case");
                };

                ref.ready().join();
                ref.send("go");

                GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>> first =
                        ref.outputs().next().join();
                assertFalse(first.done());
                assertEquals(ref.id(), first.value().actorId());
                assertEquals(0L, first.value().sequence());
                assertEquals(List.of("one"), first.value().value(),
                        "output transport must snapshot before later actor mutation");

                GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>> second =
                        ref.outputs().next().join();
                assertFalse(second.done());
                assertEquals(1L, second.value().sequence());
                assertEquals("two", second.value().value());

                ref.done().join();

                GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>> terminal =
                        ref.outputs().next().join();
                assertTrue(terminal.done());
                assertNull(terminal.value());
                assertTrue(ref.outputs().isDone());
            }
        }
    }

    @Test
    void retainedPrivateOutputConstrainsLaterNativeArenaGrowth() throws Exception {
        long limit = 16L * 1024 * 1024;
        IsolatePolicy policy = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                limit,
                64,
                Duration.ofSeconds(10),
                false);

        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<String> ref = runtime.spawnPrivateTrusted(
                    policy,
                    context -> (message, turn) -> {
                        try {
                            String payload = "x".repeat(512 * 1024);
                            turn.runtime().emitCurrentActorOutput(payload);

                            int nearLimit = Math.toIntExact(limit - 512 * 1024);
                            IllegalStateException denied = assertThrows(
                                    IllegalStateException.class,
                                    () -> ActorRuntime.currentNativeArena()
                                            .region(nearLimit));
                            assertTrue(
                                    denied.getMessage().contains("limit exceeded"),
                                    denied.toString());
                        } catch (Throwable x) {
                            failure.set(x);
                        } finally {
                            done.countDown();
                        }
                    });

            ref.send("go");
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertNull(failure.get());

            GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>> output =
                    ref.outputs().next().get(10, TimeUnit.SECONDS);
            assertFalse(output.done());
            assertEquals(512 * 1024, ((String) output.value().value()).length());

            ref.gracefulStop().get(10, TimeUnit.SECONDS);
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void outputStreamRejectsLiveActorCapabilitiesAtRuntimeBoundary() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> ref = runtime.spawnShared(
                    context -> (message, turn) ->
                            turn.runtime().emitCurrentActorOutput(turn.self()));

            ref.ready().join();
            ref.send("go");

            CompletionException failed =
                    assertThrows(CompletionException.class, () -> ref.done().join());
            assertInstanceOf(IllegalArgumentException.class, failed.getCause());
            assertTrue(
                    failed.getCause().getMessage().contains("data values only"),
                    failed.getCause().getMessage());

            CompletionException outputFailure =
                    assertThrows(
                            CompletionException.class,
                            () -> ref.outputs().next().join());
            assertSame(failed.getCause(), outputFailure.getCause());
        }
    }

    @Test
    void outputStreamIsActorProducedAndReadOnlyByApiShape() {
        assertDoesNotThrow(() ->
                ActorRuntime.ActorOutputStream.class.getMethod("next"));
        assertThrows(
                NoSuchMethodException.class,
                () -> ActorRuntime.ActorOutputStream.class.getMethod(
                        "write", Object.class));
        assertThrows(
                NoSuchMethodException.class,
                () -> ActorRuntime.ActorOutputStream.class.getMethod(
                        "send", Object.class));
    }

    @Test
    void outputStreamRejectsUnknownMutableObjectIdentity() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> ref = runtime.spawnShared(
                    context -> (message, turn) ->
                            turn.runtime().emitCurrentActorOutput(
                                    new MutableOutputBox()));

            ref.ready().join();
            ref.send("go");

            CompletionException failed =
                    assertThrows(CompletionException.class, () -> ref.done().join());
            assertInstanceOf(IllegalArgumentException.class, failed.getCause());
            assertTrue(
                    failed.getCause().getMessage().contains("not Sendable"),
                    failed.getCause().getMessage());

            CompletionException outputFailure =
                    assertThrows(
                            CompletionException.class,
                            () -> ref.outputs().next().join());
            assertSame(failed.getCause(), outputFailure.getCause());
        }
    }

}
