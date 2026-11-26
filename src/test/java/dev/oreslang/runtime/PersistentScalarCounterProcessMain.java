package dev.oreslang.runtime;

import dev.oreslang.compiler.PersistentScalarMirV1;

import java.nio.file.Path;

/**
 * JDK process-level crash/restart harness. Uses exact production source parser
 * and checked scalar MIR, not a handwritten Java counter implementation.
 */
public final class PersistentScalarCounterProcessMain {
    private static final DurableActorStore.Address ID =
            new DurableActorStore.Address("tenant", "workflow", "run", "Counter");

    private PersistentScalarCounterProcessMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("mode and SQLite DB path required");
        var plan = PersistentScalarMirV1.lower(PersistentScalarActorV1Test.SOURCE);
        try (var store = SqliteDurableActorStore.open(Path.of(args[1]))) {
            store.bootstrap(ID, plan.initial_snapshot());
            var counter = PersistentScalarActorV1.open(plan, store, ID);
            if (args[0].equals("crash")) {
                if (counter.increment("first", 1).orElseThrow() != 1L
                        || counter.increment("second", 1).orElseThrow() != 2L)
                    throw new AssertionError("pre-crash increments failed");
                System.out.println("BEFORE_CRASH=" + counter.get_count());
                System.out.flush();
                // Deliberate unclean termination: no actor hibernation, no close,
                // no shutdown hooks. Committed state must survive this boundary.
                Runtime.getRuntime().halt(0);
            } else if (args[0].equals("recover")) {
                if (counter.get_count() != 2L)
                    throw new AssertionError("snapshot not restored");
                if (counter.increment("second", 1).orElseThrow() != 2L || counter.get_count() != 2L)
                    throw new AssertionError("exact committed replay lost its original receipt");
                try {
                    counter.increment("second", 999);
                    throw new AssertionError("conflicting replay admitted");
                } catch (IllegalStateException denied) {
                    if (!"DURABLE_DELIVERY_ID_PAYLOAD_CONFLICT".equals(denied.getMessage()))
                        throw denied;
                }
                // Conflict poisons the old activation. Recover again and prove
                // the failed replay did not mutate committed state.
                counter = PersistentScalarActorV1.open(plan, store, ID);
                if (counter.get_count() != 2L)
                    throw new AssertionError("conflicting replay modified durable state");
                if (counter.increment("third", 1).orElseThrow() != 3L)
                    throw new AssertionError("post-crash transition lost");
                System.out.println("AFTER_RECOVERY=" + counter.get_count());
                counter.close();
            } else {
                throw new IllegalArgumentException("unsupported process mode");
            }
        }
    }
}
