package dev.oreslang.runtime;

import dev.oreslang.compiler.PersistentScalarMirV1;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Standalone process-crash fixture. The crash path calls Runtime.halt(0)
 * immediately after observing a committed actor output, skipping all
 * shutdown hooks, actor teardown, hibernate and JDBC connection close.
 */
public final class AsyncPersistentScalarRuntimeProcessMain {
    private static final DurableActorStore.Address KEY =
            new DurableActorStore.Address("tenant", "work", "run", "Counter");

    private AsyncPersistentScalarRuntimeProcessMain() { }

    private static void assertOutput(
            ActorRuntime.ActorRef<Object> actor, String id, long value) throws Exception {
        var step = actor.outputs().next().get(10, TimeUnit.SECONDS);
        if (step.done() || !List.of(id, value).equals(step.value().value()))
            throw new AssertionError("unexpected committed async actor output: " + step);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || (!"crash".equals(args[0]) && !"recover".equals(args[0])))
            throw new IllegalArgumentException("expected crash|recover and SQLite file");
        var io = AsyncSqliteDurableProviderV1.open(Path.of(args[1]), 16);
        io.ready().toCompletableFuture().get(10, TimeUnit.SECONDS);
        String source = PersistentScalarActorV1Test.SOURCE;
        var plan = PersistentScalarMirV1.lower(source);
        if ("crash".equals(args[0]))
            io.bootstrap(KEY, plan.initial_snapshot()).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var prepared = AsyncPersistentScalarCodeExecutorV1
                    .prepare(source, images, io, KEY).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            var actor = runtime.spawnPreparedPersistentScalarActor(prepared);
            actor.ready().get(10, TimeUnit.SECONDS);

            if ("crash".equals(args[0])) {
                actor.send(PersistentScalarCodeExecutor.increment_message("first", 1));
                assertOutput(actor, "first", 1);
                actor.send(PersistentScalarCodeExecutor.increment_message("second", 1));
                assertOutput(actor, "second", 2);
                System.out.println("ASYNC_DURABLE_BEFORE_CRASH=2");
                System.out.flush();
                // Deliberately bypasses try-with-resources cleanup and JDBC close.
                Runtime.getRuntime().halt(0);
            }

            actor.send(PersistentScalarCodeExecutor.get_count_message("recovered"));
            assertOutput(actor, "recovered", 2);
            actor.send(PersistentScalarCodeExecutor.increment_message("first", 1));
            assertOutput(actor, "first", 1);
            actor.send(PersistentScalarCodeExecutor.increment_message("third", 1));
            assertOutput(actor, "third", 3);
            System.out.println("ASYNC_DURABLE_AFTER_RECOVERY=3");
            System.out.flush();
            actor.gracefulStop().get(10, TimeUnit.SECONDS);
        } finally {
            io.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
