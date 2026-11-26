package dev.oreslang.runtime;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class CountedSelectActorCursorTest {
    @Test void pendingAndReadyRoundsResumeOnlyOnOwningActorAfterDrain() throws Exception {
        var channel = new ChannelRuntime.Channel<Long>(2);
        var armed = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var result = new AtomicReference<List<Long>>();
        var cursorRef = new AtomicReference<CountedSelectReadCursor>();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                var c = CountedSelectReadCursor.actorOwned(runtime,2,List.of(channel.output()),
                        List.of(),ChannelRuntime.SelectPolicy.FAIR);
                cursorRef.set(c);
                c.beginActorRound(() -> {
                    assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                    assertTrue(c.retireRound());
                    c.beginActorRound(() -> {
                        assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                        assertTrue(c.retireRound());
                        result.set(c.result());
                        done.countDown();
                    });
                });
                armed.countDown();
            });
            actor.send("start");
            assertTrue(armed.await(5,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, cursorRef.get()::state);
            assertEquals(1,channel.pendingRegistrations());
            assertTrue(channel.tryWrite(21L));
            assertTrue(channel.tryWrite(42L));
            assertTrue(done.await(5,TimeUnit.SECONDS));
            assertEquals(List.of(21L,42L),result.get());
            assertEquals(0,channel.pendingRegistrations());
            actor.stop(); assertTrue(actor.awaitTermination(5,TimeUnit.SECONDS));
        }
    }

    @Test void stopRevokesPendingRoundWithoutCancellingProducerOrRunningLateArm() throws Exception {
        var f = new OresFuture<Long>();
        var armed = new CountDownLatch(1);
        var runs = new AtomicInteger();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                var c = CountedSelectReadCursor.actorOwned(runtime,2,List.of(),
                        List.of(f),ChannelRuntime.SelectPolicy.FAIR);
                c.beginActorRound(() -> { runs.incrementAndGet(); c.retireRound(); });
                armed.countDown();
            });
            actor.send("start");
            assertTrue(armed.await(5,TimeUnit.SECONDS));
            assertEquals(1,f.pendingRuntimeWaiterCount());
            actor.stop(); assertTrue(actor.awaitTermination(5,TimeUnit.SECONDS));
            assertEquals(0,f.pendingRuntimeWaiterCount());
            assertFalse(f.isDone());
            assertTrue(f.completeFromRuntime(42L));
            assertEquals(0,runs.get());
        }
    }
}
