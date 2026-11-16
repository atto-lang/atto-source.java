package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

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

    @Test
    void immediateOutputPollCopiesFrozenDataAndDistinguishesEmptyFromEof() {
        for (ActorRuntime.ActorKind kind :
                List.of(ActorRuntime.ActorKind.PRIVATE, ActorRuntime.ActorKind.SHARED)) {
            try (ActorRuntime runtime = new ActorRuntime()) {
                ActorRuntime.ActorRef<String> ref = switch (kind) {
                    case PRIVATE -> runtime.spawnPrivate(context -> (message, turn) -> {
                        ArrayList<String> mutable = new ArrayList<>();
                        mutable.add("before");
                        turn.runtime().emitCurrentActorOutput(mutable);
                        mutable.add("after");
                        turn.runtime().emitCurrentActorOutput("last");
                        turn.runtime().endCurrentActor();
                    });
                    case SHARED -> runtime.spawnShared(context -> (message, turn) -> {
                        ArrayList<String> mutable = new ArrayList<>();
                        mutable.add("before");
                        turn.runtime().emitCurrentActorOutput(mutable);
                        mutable.add("after");
                        turn.runtime().emitCurrentActorOutput("last");
                        turn.runtime().endCurrentActor();
                    });
                    case UNTRUSTED -> throw new AssertionError("not part of this case");
                };
                ref.ready().join();
                assertTrue(ref.outputs().tryNext().isEmpty(),
                        "nonparking poll must not await a future message");
                assertFalse(ref.outputs().isDone(), "open, empty output is not EOF");
                ref.send("go");
                ref.done().join();

                var first = ref.outputs().tryNext().orElseThrow();
                assertEquals(0L, first.sequence());
                assertEquals(List.of("before"), first.value(),
                        "poll must deliver a frozen, owned output snapshot");
                var second = ref.outputs().tryNext().orElseThrow();
                assertEquals(1L, second.sequence());
                assertEquals("last", second.value());
                assertTrue(ref.outputs().tryNext().isEmpty());
                assertTrue(ref.outputs().isDone(), "EOF is visible only after drain");
            }
        }
    }


    /**
     * Regression guard for a drained failed channel: tryRead() throws on
     * exceptional closure, so the output stream must still leave the parent's
     * retained-stream registry. This is host/JVM lifecycle evidence only.
     */
    @Test
    void failedImmediateOutputPollDoesNotRetainDrainedStream() {
        for (ActorRuntime.ActorKind kind :
                List.of(ActorRuntime.ActorKind.PRIVATE, ActorRuntime.ActorKind.SHARED)) {
            try (ActorRuntime runtime = new ActorRuntime()) {
                ActorRuntime.ActorRef<String> ref = switch (kind) {
                    case PRIVATE -> runtime.spawnPrivate(context -> (message, turn) -> {
                        throw new IllegalStateException("poll-failure-without-output");
                    });
                    case SHARED -> runtime.spawnShared(context -> (message, turn) -> {
                        throw new IllegalStateException("poll-failure-without-output");
                    });
                    case UNTRUSTED -> throw new AssertionError("not part of this case");
                };
                ref.ready().join();
                ref.send("go");
                CompletionException terminated =
                        assertThrows(CompletionException.class, () -> ref.done().join());
                var stream = ref.outputs();
                assertTrue(retainsOutputStream(runtime, stream),
                        "before observation, the runtime retains the terminated stream");

                CompletionException failed =
                        assertThrows(CompletionException.class, stream::tryNext);
                assertSame(terminated.getCause(), failed.getCause(),
                        "nonparking poll must preserve the actual actor failure");
                assertTrue(stream.isDone());
                assertFalse(retainsOutputStream(runtime, stream),
                        "failed nonparking poll must deregister drained actor output");
            }
        }
    }

    @Test
    void immediatePollDrainsBufferedOutputBeforeSurfacingActorFailure() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> ref = runtime.spawnShared(
                    context -> (message, turn) -> {
                        turn.runtime().emitCurrentActorOutput("published-before-error");
                        throw new IllegalArgumentException("actor-failed-after-publish");
                    });
            ref.ready().join();
            ref.send("go");
            CompletionException terminated =
                    assertThrows(CompletionException.class, () -> ref.done().join());

            var stream = ref.outputs();
            var buffered = stream.tryNext().orElseThrow();
            assertEquals("published-before-error", buffered.value());
            assertEquals(0L, buffered.sequence());

            CompletionException failed =
                    assertThrows(CompletionException.class, stream::tryNext);
            assertSame(terminated.getCause(), failed.getCause(),
                    "buffered output must not hide the terminal actor failure");
            assertTrue(stream.isDone());
            assertFalse(retainsOutputStream(runtime, stream));
        }
    }

    @Test
    void immediatePollDoesNotStealOutputFromRegisteredAsyncReader() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> ref = runtime.spawnPrivate(
                    context -> (message, turn) -> {
                        turn.runtime().emitCurrentActorOutput("one-output");
                        turn.runtime().endCurrentActor();
                    });
            ref.ready().join();

            var pending = ref.outputs().next();
            assertTrue(ref.outputs().tryNext().isEmpty(),
                    "poll of an open empty stream must not manufacture output");
            ref.send("go");

            var committed = pending.join();
            assertFalse(committed.done());
            assertEquals("one-output", committed.value().value());
            assertEquals(0L, committed.value().sequence());
            ref.done().join();

            assertTrue(ref.outputs().tryNext().isEmpty(),
                    "the same output must not be delivered a second time");
            assertTrue(ref.outputs().isDone());
        }
    }

    private static boolean retainsOutputStream(
            ActorRuntime runtime, ActorRuntime.ActorOutputStream<?> stream) {
        try {
            var field = ActorRuntime.class.getDeclaredField("actorOutputStreams");
            field.setAccessible(true);
            return ((java.util.Set<?>) field.get(runtime)).contains(stream);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot inspect output stream retention", failure);
        }
    }

}
