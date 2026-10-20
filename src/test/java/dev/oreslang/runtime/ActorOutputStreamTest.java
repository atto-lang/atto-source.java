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
    @SuppressWarnings("unchecked")
    void runtimeDrainRetainsDequeuedEnvelopeWhenOutputRefundFails()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var emitted = new java.util.concurrent.CountDownLatch(1);
            ActorRuntime.ActorRef<String> ref = runtime.spawnShared(
                    context -> (message, turn) -> {
                        turn.runtime().emitCurrentActorOutput("queued");
                        emitted.countDown();
                    });

            ref.ready().join();
            ref.send("go");
            assertTrue(emitted.await(5, java.util.concurrent.TimeUnit.SECONDS));

            long reserved = runtime.sharedMemoryBytes();
            assertTrue(reserved > 0L);

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            var accountingField =
                    ActorRuntime.class.getDeclaredField("sharedMemoryBytes");
            actorsField.setAccessible(true);
            accountingField.setAccessible(true);
            var actors = (java.util.Map<Object, Object>) actorsField.get(runtime);
            Object cell = actors.get(ref.id());
            assertNotNull(cell);

            var outputBytesField = cell.getClass().getDeclaredField("outputBytes");
            outputBytesField.setAccessible(true);
            var outputBytes = (java.util.concurrent.atomic.AtomicLong)
                    outputBytesField.get(cell);
            assertEquals(reserved, outputBytes.get());

            ActorRuntime.ActorOutputStream<Object> stream = ref.outputs();
            var closeMethod = stream.getClass().getDeclaredMethod(
                    "closeAndDrainFromRuntime");
            var pendingField = stream.getClass().getDeclaredField(
                    "closePendingEnvelope");
            var completeField = stream.getClass().getDeclaredField(
                    "closeCleanupComplete");
            closeMethod.setAccessible(true);
            pendingField.setAccessible(true);
            completeField.setAccessible(true);

            var accounting = (java.util.concurrent.atomic.AtomicLong)
                    accountingField.get(runtime);
            accounting.set(0L);

            var failure = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> closeMethod.invoke(stream));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains(
                    "shared actor memory accounting underflow"));

            assertNotNull(pendingField.get(stream),
                    "dequeued output envelope must remain the exact retry owner");
            assertFalse(completeField.getBoolean(stream));
            assertEquals(reserved, outputBytes.get(),
                    "failed aggregate refund cannot consume actor-local output debit");

            accounting.set(reserved);
            closeMethod.invoke(stream);

            assertNull(pendingField.get(stream));
            assertTrue(completeField.getBoolean(stream));
            assertEquals(0L, outputBytes.get());
            assertEquals(0L, runtime.sharedMemoryBytes());

            ref.stop();
            assertTrue(ref.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void consumedOutputRefundFailureRetainsReadOwnerForRuntimeRetry()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var emitted = new java.util.concurrent.CountDownLatch(1);
            ActorRuntime.ActorRef<String> ref = runtime.spawnShared(
                    context -> (message, turn) -> {
                        turn.runtime().emitCurrentActorOutput("read");
                        emitted.countDown();
                    });

            ref.ready().join();
            ref.send("go");
            assertTrue(emitted.await(5, java.util.concurrent.TimeUnit.SECONDS));

            long reserved = runtime.sharedMemoryBytes();
            assertTrue(reserved > 0L);

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            var accountingField =
                    ActorRuntime.class.getDeclaredField("sharedMemoryBytes");
            actorsField.setAccessible(true);
            accountingField.setAccessible(true);
            var actors = (java.util.Map<Object, Object>) actorsField.get(runtime);
            Object cell = actors.get(ref.id());
            assertNotNull(cell);

            var outputBytesField = cell.getClass().getDeclaredField("outputBytes");
            outputBytesField.setAccessible(true);
            var outputBytes = (java.util.concurrent.atomic.AtomicLong)
                    outputBytesField.get(cell);

            ActorRuntime.ActorOutputStream<Object> stream = ref.outputs();
            var ownersField = stream.getClass().getDeclaredField(
                    "readReleaseOwners");
            var completeField = stream.getClass().getDeclaredField(
                    "closeCleanupComplete");
            var closeMethod = stream.getClass().getDeclaredMethod(
                    "closeAndDrainFromRuntime");
            ownersField.setAccessible(true);
            completeField.setAccessible(true);
            closeMethod.setAccessible(true);

            var accounting = (java.util.concurrent.atomic.AtomicLong)
                    accountingField.get(runtime);
            accounting.set(0L);

            CompletionException failure = assertThrows(
                    CompletionException.class, () -> stream.next().join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains(
                    "shared actor memory accounting underflow"));

            var owners = (java.util.List<Object>) ownersField.get(stream);
            assertEquals(1, owners.size(),
                    "failed consumer release must retain one exact envelope owner");
            assertEquals(reserved, outputBytes.get());
            assertFalse(completeField.getBoolean(stream));

            accounting.set(reserved);
            closeMethod.invoke(stream);

            assertTrue(owners.isEmpty());
            assertTrue(completeField.getBoolean(stream));
            assertEquals(0L, outputBytes.get());
            assertEquals(0L, runtime.sharedMemoryBytes());

            ref.stop();
            assertTrue(ref.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
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
