package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorReadinessLifecycleTest {

    @Test
    void readyInitializesActorWithoutAnInitialMailboxMessage() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger created = new AtomicInteger();
            ActorRuntime.ActorRef<String> ref = runtime.spawnPrivate(
                    () -> {
                        created.incrementAndGet();
                        return (message, context) -> { };
                    });

            assertFalse(ref.ready().cancel(false),
                    "guest observers must not cancel runtime-owned readiness");
            ref.ready().get(5, TimeUnit.SECONDS);
            assertEquals(1, created.get());
            assertTrue(ref.isAlive());

            ref.stop();
            ref.done().get(5, TimeUnit.SECONDS);
            assertFalse(ref.isAlive());
            assertFalse(ref.done().cancel(false),
                    "a finalized observer cannot change actor lifecycle state");
        }
    }

    @Test
    void stopBeforeStartFailsReadyAndCompletesDone() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> ref = runtime.spawnPrivate(
                    () -> (message, context) -> { });

            ref.stop();
            ExecutionException notReady = assertThrows(
                    ExecutionException.class,
                    () -> ref.ready().get(5, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorTerminatedException.class,
                    notReady.getCause());
            ref.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void initializationFailurePropagatesToReadyAndDone() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> ref = runtime.spawnPrivate(
                    () -> { throw new IllegalStateException("constructor failed"); });

            ExecutionException ready = assertThrows(
                    ExecutionException.class,
                    () -> ref.ready().get(5, TimeUnit.SECONDS));
            assertEquals("constructor failed", ready.getCause().getMessage());

            ExecutionException done = assertThrows(
                    ExecutionException.class,
                    () -> ref.done().get(5, TimeUnit.SECONDS));
            assertEquals("constructor failed", done.getCause().getMessage());
        }
    }

    @Test
    void readyDoesNotExposeMutableFutureCompletionToCallers() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> ref = runtime.spawnShared(
                    () -> (message, context) -> { });

            assertFalse(ref.ready().cancel(true));
            assertFalse(ref.done().cancel(true));
            ref.ready().get(5, TimeUnit.SECONDS);
            ref.stop();
            ref.done().get(5, TimeUnit.SECONDS);
        }
    }
    @Test
    void incompletePhysicalCloseKeepsRuntimeResourcesUntilCarrierExits() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        AtomicBoolean releaseCarrier = new AtomicBoolean(false);
        CountDownLatch executing = new CountDownLatch(1);
        try {
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(
                    () -> (message, context) -> {
                        executing.countDown();
                        // Deliberately noncooperative guest turn: physical exit
                        // must be observed, not inferred from logical STOP or
                        // executor shutdownNow() interrupt delivery.
                        while (!releaseCarrier.get()) {
                            try {
                                Thread.sleep(2);
                            } catch (InterruptedException ignored) {
                                // Simulates an untrusted turn ignoring interrupts.
                            }
                        }
                    });
            actor.send("block");
            assertTrue(executing.await(5, TimeUnit.SECONDS));

            IllegalStateException incomplete = assertThrows(
                    IllegalStateException.class, runtime::close);
            String diagnostic = incomplete.getMessage();
            assertTrue(diagnostic.contains(actor.id().toString()), diagnostic);
            assertTrue(diagnostic.contains("carrierActive=true"), diagnostic);
            assertTrue(diagnostic.contains("activeTurns=1"), diagnostic);
            assertTrue(diagnostic.contains("pendingActorStates="), diagnostic);
            assertFalse(actor.done().isDone(),
                    "logical STOP cannot publish get_done before physical guest exit");

            // A rejected close must not clear actor registration/shared
            // resources. Releasing the carrier permits a subsequent clean
            // close without inventing a successful physical revocation.
            releaseCarrier.set(true);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            runtime.close();
        } finally {
            releaseCarrier.set(true);
            // If an assertion fails, leave the carrier able to exit so no
            // noncooperative guest thread survives this test process.
            try {
                runtime.close();
            } catch (IllegalStateException ignored) {
                // The primary assertion failure remains authoritative.
            }
        }
    }

}
