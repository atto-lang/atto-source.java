package dev.oreslang.runtime;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * External acceptance probe invoked with -Xss256k. A real recursive
 * StackOverflowError occurs inside actor A on a single SHARED worker.
 * The same physical carrier must subsequently execute actor B, and
 * A's terminal failure must be actor-local and stackless.
 *
 * This JVM process does not emulate JNI crashes or arbitrary VM corruption.
 */
public final class ActorStackOverflowLowStackProbe {
    private static volatile long depthObserved;
    private ActorStackOverflowLowStackProbe() { }

    private static long recurse(long depth) {
        depthObserved = depth;
        return recurse(depth + 1) + 1; // non-tail; must consume JVM stack
    }

    public static void main(String[] args) throws Exception {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<Thread> firstThread = new AtomicReference<>();
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            var actorA = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                firstThread.set(Thread.currentThread());
                recurse(0);
            });
            var actorB = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                secondThread.set(Thread.currentThread());
                turn.self().stop();
            });

            actorA.send("overflow");
            if (!actorA.awaitTermination(15, TimeUnit.SECONDS)
                    || !(actorA.failure().orElse(null)
                            instanceof ActorStackOverflowException)) {
                throw new AssertionError("actor A did not fail locally after native overflow");
            }
            actorB.send("alive");
            if (!actorB.awaitTermination(15, TimeUnit.SECONDS)
                    || actorB.failure().isPresent()
                    || firstThread.get() == null
                    || firstThread.get() != secondThread.get()) {
                throw new AssertionError(
                        "actor B or its reused shared carrier was damaged by A");
            }
            System.out.println("ACTOR_NATIVE_STACK_OVERFLOW=A_FAILS_B_SURVIVES");
        }
    }
}
