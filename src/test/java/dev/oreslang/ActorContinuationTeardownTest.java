package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresScheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorContinuationTeardownTest {

    @Test
    void concurrentHostStopCannotAdmitFreshActorSourceWork()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch inActorTurn = new CountDownLatch(1);
            CountDownLatch releaseTurn = new CountDownLatch(1);
            AtomicInteger rejectedAdmissions = new AtomicInteger();
            AtomicInteger guestSourceStarts = new AtomicInteger();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> {
                inActorTurn.countDown();
                try {
                    assertTrue(releaseTurn.await(5, TimeUnit.SECONDS));
                    try {
                        context.runtime().startActorTask(resume -> {
                            guestSourceStarts.incrementAndGet();
                            return OresScheduler.done(null);
                        });
                        unexpected.set(new AssertionError(
                                "fresh source task was admitted after STOP"));
                    } catch (ActorRuntime.ActorTerminatedException expected) {
                        rejectedAdmissions.incrementAndGet();
                    }
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });
            actor.send(1);
            assertTrue(inActorTurn.await(5, TimeUnit.SECONDS));
            try {
                actor.stop();
            } finally {
                releaseTurn.countDown();
            }
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
            assertNull(unexpected.get(), "STOP must reject registration deterministically");
            assertEquals(1, rejectedAdmissions.get());
            assertEquals(0, guestSourceStarts.get(),
                    "no post-STOP task may execute even if its carrier was active");
        }
    }
    @Test
    void gracefulStopCancelsQueuedSourceTasksWithoutRunningThem() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            int tasks = 256;
            CountDownLatch entered = new CountDownLatch(1);
            AtomicInteger taskBodies = new AtomicInteger();

            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                for (int i = 0; i < tasks; i++) {
                    context.runtime().startActorTask(resume -> {
                        taskBodies.incrementAndGet();
                        return OresScheduler.done(null);
                    });
                }
                context.self().stop();
            });

            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
            assertEquals(0, taskBodies.get(),
                    "source continuations queued behind a stopped actor must not run");
        }
    }
}
