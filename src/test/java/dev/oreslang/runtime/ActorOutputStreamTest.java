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
    void shutdownSealWinsBufferedOutputReadArbitration() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        try {
            ActorRuntime.ActorRef<String> ref = runtime.spawnShared(
                    context -> (message, turn) -> {
                        turn.runtime().emitCurrentActorOutput("buffered-1");
                        turn.runtime().emitCurrentActorOutput("buffered-2");
                        turn.runtime().endCurrentActor();
                    });
            ref.ready().join();
            ref.send("go");
            ref.done().join();

            java.lang.reflect.Field lifecycle =
                    ActorRuntime.class.getDeclaredField("runtimeLifecycleLock");
            lifecycle.setAccessible(true);
            Object lifecycleLock = lifecycle.get(runtime);

            java.util.concurrent.atomic.AtomicReference<
                    GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>>> observed =
                            new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> readerFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();

            Thread reader;
            synchronized (lifecycleLock) {
                reader = Thread.ofPlatform().daemon(true).start(() -> {
                    try {
                        observed.set(ref.outputs().next().get(
                                5, java.util.concurrent.TimeUnit.SECONDS));
                    } catch (Throwable failure) {
                        readerFailure.set(failure);
                    }
                });

                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (reader.isAlive()
                        && reader.getState() != Thread.State.BLOCKED
                        && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertTrue(reader.isAlive(),
                        "buffered output read must reach shutdown arbitration before publishing");
                assertEquals(Thread.State.BLOCKED, reader.getState(),
                        "output completion must linearize through runtimeLifecycleLock");

                // Reentrant on this host thread. The waiting output completion
                // observes the sealed runtime only after this monitor releases.
                runtime.sealShutdownAdmission();
            }

            reader.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));
            assertFalse(reader.isAlive());
            assertNull(readerFailure.get(), String.valueOf(readerFailure.get()));
            assertNotNull(observed.get());
            assertTrue(observed.get().done(),
                    "shutdown winning arbitration must hide the buffered output");
            assertNull(observed.get().value());
            assertTrue(ref.outputs().isDone());

            java.lang.reflect.Field streamsField =
                    ActorRuntime.class.getDeclaredField("actorOutputStreams");
            streamsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Set<ActorRuntime.ActorOutputStream<?>> streams =
                    (java.util.Set<ActorRuntime.ActorOutputStream<?>>)
                            streamsField.get(runtime);
            assertTrue(streams.contains(ref.outputs()),
                    "logical shutdown must retain physically buffered output for teardown");
        } finally {
            runtime.close();
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
            assertInstanceOf(ActorRuntime.ActorFailureSnapshotException.class, failed.getCause());
            assertInstanceOf(IllegalArgumentException.class, ref.failure().orElseThrow());
            assertTrue(
                    ref.failure().orElseThrow().getMessage().contains("data values only"),
                    ref.failure().orElseThrow().getMessage());

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
            assertInstanceOf(ActorRuntime.ActorFailureSnapshotException.class, failed.getCause());
            assertInstanceOf(IllegalArgumentException.class, ref.failure().orElseThrow());
            assertTrue(
                    ref.failure().orElseThrow().getMessage().contains("not Sendable"),
                    ref.failure().orElseThrow().getMessage());

            CompletionException outputFailure =
                    assertThrows(
                            CompletionException.class,
                            () -> ref.outputs().next().join());
            assertSame(failed.getCause(), outputFailure.getCause());
        }
    }

}
