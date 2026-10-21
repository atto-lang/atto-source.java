package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class SupervisorRestartPermitTest {

    @Test
    void restartPermitRequiresExactGenerationAndRetiredDoneBarrier() throws Exception {
        CountDownLatch exitHookEntered = new CountDownLatch(1);
        CountDownLatch releaseExitHook = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 4))) {
            runtime.setActorExitHook(domain -> {
                exitHookEntered.countDown();
                try {
                    if (!releaseExitHook.await(8, TimeUnit.SECONDS)) {
                        throw new AssertionError("exit hook release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });

            ActorRuntime.ActorRef<String> failed = runtime.spawnShared(
                    () -> (message, context) -> {
                        throw new IllegalStateException("restart-me");
                    });
            failed.getReady().get(3, TimeUnit.SECONDS);
            failed.send("fail");
            assertTrue(exitHookEntered.await(3, TimeUnit.SECONDS));

            ActorRuntime.SupervisorControlEvent event = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (event == null && System.nanoTime() < deadline) {
                event = runtime.peekSupervisorControlEvent().orElse(null);
                if (event == null) Thread.sleep(1);
            }
            assertNotNull(event);
            assertEquals(failed.id(), event.actorId());
            assertEquals(failed.generation(), event.generation());

            assertTrue(runtime.tryAcquireSupervisorRestartPermit(
                            failed.id(), failed.generation() + 1).isEmpty());
            assertTrue(runtime.tryAcquireSupervisorRestartPermit(
                            failed.id(), failed.generation()).isEmpty(),
                    "terminal CONTROL alone must not authorize restart");
            assertFalse(failed.getDone().isDone());

            releaseExitHook.countDown();
            ExecutionException doneFailure = assertThrows(
                    ExecutionException.class,
                    () -> failed.getDone().get(3, TimeUnit.SECONDS));
            assertEquals("restart-me", doneFailure.getCause().getMessage());

            ActorRuntime.SupervisorRestartPermit permit =
                    runtime.tryAcquireSupervisorRestartPermit(
                            failed.id(), failed.generation()).orElseThrow();
            assertEquals(failed.id(), permit.failedActorId());
            assertEquals(failed.generation(), permit.failedGeneration());
            assertEquals(0, runtime.supervisorControlBacklog());

            ActorRuntime.ActorRef<String> replacement =
                    runtime.restartAfterFailure(permit);
            assertEquals(failed.id(), replacement.id());
            assertTrue(replacement.generation() > failed.generation());
            replacement.getReady().get(3, TimeUnit.SECONDS);

            assertThrows(
                    ActorRuntime.ActorInvalidReferenceException.class,
                    () -> failed.send("stale-generation"));
            failed.stop();
            assertTrue(replacement.isAlive(),
                    "old generation lifecycle operations must not stop replacement");
            assertTrue(runtime.snapshot(failed).isEmpty());
            assertTrue(runtime.snapshot(replacement).isPresent());

            assertThrows(IllegalStateException.class,
                    () -> runtime.restartAfterFailure(permit));

            replacement.stop();
            replacement.getDone().get(3, TimeUnit.SECONDS);
        } finally {
            releaseExitHook.countDown();
        }
    }

    @Test
    void ordinaryAcknowledgementDoesNotFabricateRestartPermit() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> failed = runtime.spawnShared(
                    () -> (message, context) -> {
                        throw new IllegalArgumentException("no-restart");
                    });
            failed.send("fail");
            assertTrue(failed.awaitTermination(3, TimeUnit.SECONDS));

            ActorRuntime.SupervisorControlEvent event = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (event == null && System.nanoTime() < deadline) {
                event = runtime.peekSupervisorControlEvent().orElse(null);
                if (event == null) Thread.sleep(1);
            }
            assertNotNull(event);
            assertThrows(ExecutionException.class,
                    () -> failed.getDone().get(3, TimeUnit.SECONDS));
            assertTrue(runtime.acknowledgeSupervisorControlEvent(
                    event.actorId(), event.generation()));
            assertTrue(runtime.tryAcquireSupervisorRestartPermit(
                    event.actorId(), event.generation()).isEmpty());
        }
    }
}
