package dev.oreslang.runtime;

import dev.oreslang.compiler.PersistentScalarMirV1;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes a real actor on the existing scheduler across two distinct OS
 * processes. Mode "crash" terminates without ActorRuntime.close(), source
 * actor teardown, SQLite close or JVM shutdown hooks.
 */
public final class PersistentScalarRuntimeProcessMain {
    private static final DurableActorStore.Address KEY =
            new DurableActorStore.Address("tenant", "workflow", "run", "Counter");

    private PersistentScalarRuntimeProcessMain() { }

    private static void receipt(ActorRuntime.ActorRef<Object> actor,
                                String id, long expected) throws Exception {
        var result = actor.outputs().next().get(5, TimeUnit.SECONDS);
        if (result.done() || !List.of(id, expected).equals(result.value().value()))
            throw new AssertionError("unexpected durable receipt: " + result);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("mode + database required");
        var plan = PersistentScalarMirV1.lower(PersistentScalarActorV1Test.SOURCE);
        try (var database = SqliteDurableActorStore.open(Path.of(args[1]));
             var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            database.bootstrap(KEY, plan.initial_snapshot());
            var executor = PersistentScalarCodeExecutor.compile(
                    PersistentScalarActorV1Test.SOURCE, images, database, KEY);
            var actor = runtime.spawnPersistentScalarActor(executor);
            actor.ready().get(5, TimeUnit.SECONDS);
            if (args[0].equals("crash")) {
                actor.send(PersistentScalarCodeExecutor.increment_message("one", 1));
                receipt(actor, "one", 1);
                actor.send(PersistentScalarCodeExecutor.increment_message("two", 1));
                receipt(actor, "two", 2);
                System.out.println("RUNTIME_BEFORE_CRASH=2");
                System.out.flush();
                // No actor hibernation, no runtime close, no SQLite close.
                Runtime.getRuntime().halt(0);
            } else if (args[0].equals("recover")) {
                actor.send(PersistentScalarCodeExecutor.get_count_message("read"));
                receipt(actor, "read", 2);
                // Stable delivery identity MUST NOT mutate state twice.
                actor.send(PersistentScalarCodeExecutor.increment_message("three", 1));
                receipt(actor, "three", 3);
                actor.send(PersistentScalarCodeExecutor.get_count_message("final"));
                receipt(actor, "final", 3);
                actor.gracefulStop().get(5, TimeUnit.SECONDS);
                if (actor.failure().isPresent())
                    throw new AssertionError("actor failed after recovery", actor.failure().get());
                System.out.println("RUNTIME_AFTER_RECOVERY=3");
            } else {
                throw new IllegalArgumentException("unsupported mode");
            }
        }
    }
}
