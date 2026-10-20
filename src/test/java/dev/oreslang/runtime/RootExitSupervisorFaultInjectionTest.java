package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Controlled scheduler/actor failure injection at the root CONTROL boundary.
 * No sleeps, global process termination or guest System.exit calls.
 */
@Timeout(15)
final class RootExitSupervisorFaultInjectionTest {
    private static void awaitWaiter(OresFuture<?> future) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (future.pendingRuntimeWaiterCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(1, future.pendingRuntimeWaiterCount(),
                "root task must be suspended before fault injection");
    }

    @Test
    void cancelledRootCannotBecomeExitZeroWhenLateProducerResolves() throws Exception {
        AtomicReference<OresFuture.Callback<Integer>> producer = new AtomicReference<>();
        OresFuture<Integer> gate = OresFuture.fromCallback(producer::set);
        AtomicInteger rootResumptions = new AtomicInteger();
        try (OresScheduler root = new OresScheduler(1)) {
            OresFuture<Integer> result = root.start(resume -> {
                rootResumptions.incrementAndGet();
                if (resume.initial()) return OresScheduler.await(gate);
                return OresScheduler.done((Integer) resume.value());
            });
            awaitWaiter(gate);
            assertTrue(result.cancel(false), "root cancellation should win before guest completion");
            producer.get().resolve(0);
            assertTrue(result.isCancelled(), "late successful exit must never replace cancellation");
            assertThrows(CancellationException.class, result::join);
            assertEquals(1, rootResumptions.get(), "cancelled root cannot be re-entered");
            assertEquals(0, gate.pendingRuntimeWaiterCount(), "cancelled waiter must detach");
        }
    }

    @Test
    void rootFailureCannotBeReplacedByLaterSuccessStatus() throws Exception {
        AtomicReference<OresFuture.Callback<Integer>> producer = new AtomicReference<>();
        OresFuture<Integer> gate = OresFuture.fromCallback(producer::set);
        IllegalStateException fault = new IllegalStateException("injected root failure");
        try (OresScheduler root = new OresScheduler(1)) {
            OresFuture<Integer> result = root.start(resume -> {
                if (resume.initial()) return OresScheduler.await(gate);
                if (resume.failure() != null) throw new IllegalStateException(
                        "root failure remained authoritative", resume.failure());
                return OresScheduler.done((Integer) resume.value());
            });
            awaitWaiter(gate);
            producer.get().reject(fault);
            CompletionException failure = assertThrows(CompletionException.class, result::join);
            assertEquals("root failure remained authoritative", failure.getCause().getMessage());
            assertSame(fault, failure.getCause().getCause());
            assertThrows(OresFuture.AlreadySettledException.class,
                    () -> producer.get().resolve(0),
                    "a late exit(0) cannot mutate terminal root failure");
        }
    }

    @Test
    void failedActorDoesNotPoisonIndependentRootOrHealthyActor() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        try (ActorRuntime actors = new ActorRuntime();
             OresScheduler root = new OresScheduler(1)) {
            ActorRuntime.ActorRef<String> failing = actors.spawnShared(
                    () -> (message, context) -> {
                        throw new IllegalStateException("injected actor failure");
                    });
            ActorRuntime.ActorRef<String> healthy = actors.spawnShared(
                    () -> (message, context) -> {
                        delivered.countDown();
                        context.self().stop();
                    });

            failing.send("fail");
            assertTrue(failing.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(failing.failure().isPresent(), "actor fault must remain observable");
            assertFalse(failing.isAlive());

            // Actor-local failure does not imply root process failure.
            OresFuture<Integer> rootResult = root.start(
                    resume -> OresScheduler.done(23));
            assertEquals(23, rootResult.get(3, TimeUnit.SECONDS));
            healthy.send("continue");
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            assertTrue(healthy.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(healthy.failure().isEmpty(), "healthy actor should shut down cleanly");
        }
    }
}
