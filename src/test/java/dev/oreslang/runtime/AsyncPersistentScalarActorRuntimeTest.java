package dev.oreslang.runtime;

import dev.oreslang.compiler.PersistentScalarMirV1;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime continuations, checked AttoLang source, SQLite worker
 * and committed receipts. JUnit host JVM only; not a full guest AOT claim.
 */
final class AsyncPersistentScalarActorRuntimeTest {
    @TempDir Path temp;

    private static final String SOURCE = PersistentScalarActorV1Test.SOURCE;
    private static final DurableActorStore.Address KEY =
            new DurableActorStore.Address("tenant-a", "work", "run", "Counter");

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage)
            throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static List<?> output(ActorRuntime.ActorRef<Object> actor) throws Exception {
        var step = actor.outputs().next().get(10, TimeUnit.SECONDS);
        assertFalse(step.done(), "expected committed output");
        return assertInstanceOf(List.class, step.value().value());
    }

    private static AsyncPersistentScalarCodeExecutorV1 prepare(
            SharedCodeImageStore images, AsyncSqliteDurableProviderV1 io) throws Exception {
        return await(AsyncPersistentScalarCodeExecutorV1.prepare(SOURCE, images, io, KEY));
    }

    @Test
    void realActorMailboxSuspendsAcrossIoWithoutReorderingAndRestoresOriginalResult()
            throws Exception {
        Path path = temp.resolve("async-durable.db");
        var plan = PersistentScalarMirV1.lower(SOURCE);
        var io = AsyncSqliteDurableProviderV1.open(path, 16);
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            await(io.ready());
            assertTrue(await(io.bootstrap(KEY, plan.initial_snapshot())));
            var prepared = prepare(images, io);
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                    prepared, prepared.actor_type_name());
            assertNull(actor.ready().get(10, TimeUnit.SECONDS));

            // Send three messages before waiting: the runtime mailbox must
            // suspend each source receive through SQLite completion.
            actor.send(PersistentScalarCodeExecutor.increment_message("first", 1));
            actor.send(PersistentScalarCodeExecutor.increment_message("second", 1));
            actor.send(PersistentScalarCodeExecutor.get_count_message("read"));
            assertEquals(List.of("first", 1L), output(actor));
            assertEquals(List.of("second", 2L), output(actor));
            assertEquals(List.of("read", 2L), output(actor));

            // The original committed result must be returned, not current 2.
            actor.send(PersistentScalarCodeExecutor.increment_message("first", 1));
            assertEquals(List.of("first", 1L), output(actor));
            actor.send(PersistentScalarCodeExecutor.get_count_message("still-2"));
            assertEquals(List.of("still-2", 2L), output(actor));
            assertNull(actor.gracefulStop().get(10, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), String.valueOf(actor.failure()));
        } finally {
            await(io.closeAsync());
        }

        var reopened = AsyncSqliteDurableProviderV1.open(path, 16);
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            await(reopened.ready());
            var prepared = prepare(images, reopened);
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                    prepared, prepared.actor_type_name());
            assertNull(actor.ready().get(10, TimeUnit.SECONDS));
            actor.send(PersistentScalarCodeExecutor.get_count_message("recovered"));
            assertEquals(List.of("recovered", 2L), output(actor));
            actor.send(PersistentScalarCodeExecutor.increment_message("second", 1));
            assertEquals(List.of("second", 2L), output(actor),
                    "retries return the original committed value after restart");
            actor.send(PersistentScalarCodeExecutor.increment_message("third", 3));
            assertEquals(List.of("third", 5L), output(actor));
            assertNull(actor.gracefulStop().get(10, TimeUnit.SECONDS));
        } finally {
            await(reopened.closeAsync());
        }
    }

    @Test
    void preparationReplaysBothLegacyAndTypedPendingInboxBeforeActorReady()
            throws Exception {
        Path path = temp.resolve("async-replay.db");
        var plan = PersistentScalarMirV1.lower(SOURCE);
        var io = AsyncSqliteDurableProviderV1.open(path, 16);
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            await(io.ready());
            await(io.bootstrap(KEY, plan.initial_snapshot()));
            assertTrue(await(io.enqueue(KEY, new DurableActorStore.Inbound(
                    "legacy", plan.encode(4)))));
            assertTrue(await(io.enqueue(KEY, new DurableActorStore.Inbound(
                    "typed", plan.encode_increment_message(3)))));
            var prepared = prepare(images, io);
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                    prepared, prepared.actor_type_name());
            assertNull(actor.ready().get(10, TimeUnit.SECONDS));
            actor.send(PersistentScalarCodeExecutor.get_count_message("replayed"));
            assertEquals(List.of("replayed", 7L), output(actor));
            actor.send(PersistentScalarCodeExecutor.increment_message("typed", 3));
            assertEquals(List.of("typed", 7L), output(actor));
            assertNull(actor.gracefulStop().get(10, TimeUnit.SECONDS));
        } finally {
            await(io.closeAsync());
        }
    }

    @Test
    void newEpochRevokesOldAsyncActorBeforeEnqueueOrCachedRead() throws Exception {
        Path path = temp.resolve("async-epochs.db");
        var plan = PersistentScalarMirV1.lower(SOURCE);
        var oldIo = AsyncSqliteDurableProviderV1.open(path, 16);
        var newIo = AsyncSqliteDurableProviderV1.open(path, 16);
        try (var oldImages = new SharedCodeImageStore();
             var newImages = new SharedCodeImageStore();
             var oldRuntime = new ActorRuntime(IsolatePolicy.developer());
             var newRuntime = new ActorRuntime(IsolatePolicy.developer())) {
            await(oldIo.ready());
            await(newIo.ready());
            await(oldIo.bootstrap(KEY, plan.initial_snapshot()));
            var old = prepare(oldImages, oldIo);
            var oldActor = oldRuntime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                    old, old.actor_type_name());
            assertNull(oldActor.ready().get(10, TimeUnit.SECONDS));

            var current = prepare(newImages, newIo);
            var currentActor = newRuntime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                    current, current.actor_type_name());
            assertNull(currentActor.ready().get(10, TimeUnit.SECONDS));

            oldActor.send(PersistentScalarCodeExecutor.increment_message("stale", 99));
            assertTrue(oldActor.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(oldActor.failure().isPresent());
            currentActor.send(PersistentScalarCodeExecutor.get_count_message("new"));
            assertEquals(List.of("new", 0L), output(currentActor));
            currentActor.send(PersistentScalarCodeExecutor.increment_message("valid", 2));
            assertEquals(List.of("valid", 2L), output(currentActor));
            assertNull(currentActor.gracefulStop().get(10, TimeUnit.SECONDS));
        } finally {
            await(oldIo.closeAsync());
            await(newIo.closeAsync());
        }
    }

    @Test
    void preparationFailsClosedForUnknownCodeGenerationAndFutureWireVersion()
            throws Exception {
        Path path = temp.resolve("async-schema.db");
        var plan = PersistentScalarMirV1.lower(SOURCE);
        var io = AsyncSqliteDurableProviderV1.open(path, 16);
        try (var images = new SharedCodeImageStore()) {
            await(io.ready());
            await(io.bootstrap(KEY, plan.initial_snapshot()));
            byte[] future = plan.encode_increment_message(2);
            future[7] = 2; // schema v2 is deliberately unsupported
            await(io.enqueue(KEY, new DurableActorStore.Inbound("future", future)));
            assertThrows(ExecutionException.class,
                    () -> await(AsyncPersistentScalarCodeExecutorV1.prepare(
                            SOURCE, images, io, KEY)));
            // Error cannot silently consume pending inbound or promote v2.
            var activation = await(io.activate(KEY));
            assertEquals(0L, activation.snapshot().stateVersion());
            assertEquals("future", activation.inbox().getFirst().deliveryId());
        } finally {
            await(io.closeAsync());
        }
    }
}
