package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A failing child must not erase the errors from its siblings during
 * supervisor-driven graceful shutdown.
 */
@Timeout(15)
final class ActorGroupCloseFailureAggregationTest {
    private static volatile CountDownLatch entered;
    private static volatile CountDownLatch release;

    @Test
    void reportsBothChildFailuresOnlyAfterAllWorkersFinalize() throws Exception {
        entered = new CountDownLatch(2);
        release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> first = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> (message, actorContext) -> {
                        entered.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        throw new IllegalStateException("first failing child");
                    });
            ActorRuntime.ActorRef<Integer> second = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> (message, actorContext) -> {
                        entered.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        throw new IllegalArgumentException("second failing child");
                    });
            first.send(1);
            second.send(2);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> closed = group.gracefulCloseAsync();
            assertFalse(closed.isDone(), "active workers must not finish early");
            try {
                assertSame(closed, group.gracefulCloseAsync());
                assertFalse(closed.cancel(false), "shutdown observer is runtime-owned");
            } finally {
                release.countDown();
            }
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> closed.get(5, TimeUnit.SECONDS));
            assertNotNull(error.getCause());
            assertTrue(error.getCause().getSuppressed().length >= 1,
                    "secondary failed child must be retained as suppressed failure");
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }
}
