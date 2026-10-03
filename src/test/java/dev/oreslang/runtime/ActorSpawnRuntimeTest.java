package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSpawnRuntimeTest {

    @Test
    void spawnInvocationSeparatesReadyFromCompletion() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "work",
                    (message, context) -> {
                        entered.countDown();
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        return message + "-done";
                    });

            assertNotNull(spawn.id());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(spawn.ready().isDone(), "READY must precede actor callable execution");
            assertFalse(spawn.result().isDone(), "spawn must not wait for callable completion");

            release.countDown();
            assertEquals("work-done", spawn.result().get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void spawnInvocationReturnsBeforeActorCallableCompletes() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch release = new CountDownLatch(1);

            ActorRuntime.ActorSpawn<Integer, Integer> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    7,
                    (message, context) -> {
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        return message * 6;
                    });

            assertNotNull(spawn.id());
            assertFalse(spawn.result().isDone());
            release.countDown();
            assertEquals(42, spawn.result().get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void readinessFutureYieldsTheSameActorIdentity() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "ok",
                    (message, context) -> message);

            ActorRuntime.ActorRef<String> ref = spawn.ready().get(2, TimeUnit.SECONDS);
            assertEquals(spawn.id(), ref.id());
            assertEquals("ok", spawn.result().get(2, TimeUnit.SECONDS));
        }
    }
}
