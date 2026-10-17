package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Joint actor-arena/HTTP-carrier shutdown regression. A completed mailbox
 * delivery does not imply the physical TurnExecutor has exited, and physical
 * carrier quiescence does not imply a suspended source task's native arena
 * lifetime pin may be released.
 */
@Timeout(20)
final class ActorArenaCarrierContinuationIntegrationTest {
    @Test
    void physicalCarrierQuiescenceCannotPrematurelyReleaseSuspendedArena() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        CountDownLatch sourceSuspended = new CountDownLatch(1);
        CountDownLatch carrierHeld = new CountDownLatch(1);
        CountDownLatch releaseCarrier = new CountDownLatch(1);
        AtomicBoolean heldOnce = new AtomicBoolean();
        AtomicInteger resumed = new AtomicInteger();
        AtomicReference<ActorArena> arenaRef = new AtomicReference<>();
        AtomicReference<OresFuture<Integer>> sourceRef = new AtomicReference<>();
        OresFuture<Integer> pendingIo = new OresFuture<>();

        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            if (sourceSuspended.getCount() == 0 && heldOnce.compareAndSet(false, true)) {
                carrierHeld.countDown();
                try {
                    if (!releaseCarrier.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test carrier-release deadline elapsed");
                    }
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test physical carrier interrupted", interruption);
                }
            }
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 16, 16),
                executor)) {
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() ->
                    (message, turn) -> {
                        ActorArena arena = turn.localMemory().orElseThrow()
                                .newActorArena(1, 1);
                        arenaRef.set(arena);
                        arena.allocatePersistent(41L);
                        sourceRef.set(runtime.startActorTask(resume -> {
                            if (resumed.getAndIncrement() == 0) {
                                sourceSuspended.countDown();
                                return OresScheduler.await(pendingIo);
                            }
                            return OresScheduler.done(42);
                        }));
                    });
            try {
                actor.ready().get(5, TimeUnit.SECONDS);
                actor.send("start");
                assertTrue(sourceSuspended.await(5, TimeUnit.SECONDS));
                assertTrue(carrierHeld.await(5, TimeUnit.SECONDS),
                        "guest callback must return before physical carrier exit");
                assertEquals(1, arenaRef.get().outstandingExternalLeases(),
                        "suspended source must retain its lifetime-only arena pin");

                OresFuture<Void> quiescent = runtime.quiesceAcceptedMessages(actor);
                assertFalse(quiescent.isDone(),
                        "mailbox exit must not impersonate physical carrier exit");
                releaseCarrier.countDown();
                quiescent.get(5, TimeUnit.SECONDS);
                assertEquals(1, arenaRef.get().outstandingExternalLeases(),
                        "carrier exit cannot release a still-suspended source task");
                assertFalse(sourceRef.get().isDone());
                assertFalse(arenaRef.get().physicallyClosed());

                assertTrue(actor.cancel());
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertEquals(0, arenaRef.get().outstandingExternalLeases());
                assertTrue(arenaRef.get().physicallyClosed());
                assertTrue(actor.done().isDone());

                pendingIo.completeFromRuntime(99);
                assertEquals(1, resumed.get(),
                        "late I/O completion must not re-enter a retired actor");
            } finally {
                releaseCarrier.countDown();
            }
        }
    }
}
