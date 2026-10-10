package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Distinguishes a real one-engine-per-actor collector from the logical,
 * shared-JVM memory accounting implementation.
 */
final class DedicatedUntrustedActorIsolatesTest {
    private static final String WORKER = """
            define untrusted actor IsolatedWorker as
              receive(ActorMail<String> mail): void {
                if mail.value == "stop"; do
                  self.end();
                fi
                return;
              }
            end
            """;

    @Test
    void validatesStrictMemoryAndActorCapacityBeforeCreatingEngines() {
        assertThrows(IllegalArgumentException.class, () ->
                DedicatedUntrustedActorIsolates.validateConfiguration(64L << 20, 1));
        assertThrows(IllegalArgumentException.class, () ->
                DedicatedUntrustedActorIsolates.validateConfiguration(2048L << 20, 1));
        assertThrows(IllegalArgumentException.class, () ->
                DedicatedUntrustedActorIsolates.validateConfiguration(128L << 20, 0));
        assertThrows(IllegalArgumentException.class, () ->
                DedicatedUntrustedActorIsolates.validateConfiguration(128L << 20, 257));
        assertDoesNotThrow(() ->
                DedicatedUntrustedActorIsolates.validateConfiguration(256L << 20, 2));
    }

    @Test
    void refusesAdmissionAfterCloseWithoutAllocatingAnEngine() {
        var pool = new DedicatedUntrustedActorIsolates(256L << 20, 2);
        assertEquals(0, pool.activeActors());
        pool.close();
        pool.close();
        assertThrows(IllegalStateException.class,
                () -> pool.spawn(WORKER, "worker", "IsolatedWorker"));
        assertEquals(0, pool.activeActors());
    }

    @Test
    void refusesNullInputWithoutAllocatingAnEngine() {
        try (var pool = new DedicatedUntrustedActorIsolates(256L << 20, 2)) {
            assertThrows(NullPointerException.class,
                    () -> pool.spawn(null, "worker", "IsolatedWorker"));
            assertThrows(NullPointerException.class,
                    () -> pool.spawn(WORKER, null, "IsolatedWorker"));
            assertEquals(0, pool.activeActors());
        }
    }

    @Test
    @DisabledIfEnvironmentVariable(
            named="ORES_TEST_DEDICATED_ACTOR_ISOLATE", matches="true")
    void missingGuestNativeImageNeverFallsBackToHostJvm() {
        // Run in CI without Oreslang's native language isolate dependency.
        // A non-Graal JVM cannot silently instantiate host actors here.
        try (var pool = new DedicatedUntrustedActorIsolates(256L << 20, 1)) {
            assertThrows(RuntimeException.class,
                    () -> pool.spawn(WORKER, "must-not-fallback", "IsolatedWorker"));
            assertEquals(0, pool.activeActors());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(
            named="ORES_TEST_DEDICATED_ACTOR_ISOLATE", matches="true")
    void twoRealSourceActorsHaveDifferentPhysicalGcEngines() throws Exception {
        try (var pool = new DedicatedUntrustedActorIsolates(256L << 20, 2);
             var first = pool.spawn(WORKER, "actor-one", "IsolatedWorker");
             var second = pool.spawn(WORKER, "actor-two", "IsolatedWorker")) {
            assertEquals(2, pool.activeActors());
            assertTrue(first.independentlyCollectable());
            assertTrue(second.independentlyCollectable());
            assertNotEquals(first.identity(), second.identity());
            assertFalse(first.sharesPhysicalCollectorWith(second),
                    "distinct Oreslang actors must not reuse an Engine/GC");
            assertThrows(SecurityException.class, () -> first.send(new Object()));
            first.send("stop");
            second.send("stop");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!first.done() || !second.done())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(first.done() && second.done());
        }
    }
}
