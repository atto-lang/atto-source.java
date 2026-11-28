package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Saturated actor-carrier capacity must stage NIO completion without growing
 * physical workers, executing subscribers on the selector, or losing work.
 */
final class NioReadinessCarrierSaturationTest {
    @Test void saturatedWarmCarrierStagesThenDrainsWithoutWorkerGrowth() throws Exception {
        var workers = new AdaptiveCarrierExecutor(
                0, 1, 1,
                task -> Thread.ofPlatform().daemon(true)
                        .name("nio-saturation-carrier").unstarted(task));
        var group = new NioReadinessGroup(1, 8, 8);
        var pipe = Pipe.open();
        var releaseBlocker = new CountDownLatch(1);
        try {
            group.bindVmCompletionExecutor(workers::executeWithoutCarrierGrowth, false);
            workers.onTaskCompleted(group::retryStagedNotifications);

            // Warm exactly one verified JVM carrier before the selector is
            // allowed to submit no-growth completion work.
            var warmed = new CountDownLatch(1);
            workers.execute(warmed::countDown);
            assertTrue(warmed.await(3, TimeUnit.SECONDS));
            long warmDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (workers.startedWorkerCount() != 1
                    && System.nanoTime() < warmDeadline)
                Thread.sleep(1);
            assertEquals(1, workers.startedWorkerCount());

            var blockerEntered = new CountDownLatch(1);
            workers.execute(() -> {
                blockerEntered.countDown();
                try {
                    if (!releaseBlocker.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("saturation blocker timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("saturation blocker interrupted", interrupted);
                }
            });
            assertTrue(blockerEntered.await(3, TimeUnit.SECONDS));

            // Occupy the only ready-queue slot. The selector's later
            // executeWithoutCarrierGrowth() must reject rather than starting
            // another physical carrier.
            var fillerRan = new CountDownLatch(1);
            workers.execute(fillerRan::countDown);
            assertEquals(1, workers.getQueue().size());

            pipe.source().configureBlocking(false);
            pipe.sink().configureBlocking(false);
            var callbackCount = new AtomicInteger();
            var callbackOnSelector = new AtomicBoolean();
            var ready = group.watch(pipe.source(), SelectionKey.OP_READ);
            ready.whenCompleteRuntime((events, failure) -> {
                if (NioReadinessGroup.onReactorThread())
                    callbackOnSelector.set(true);
                callbackCount.incrementAndGet();
            });
            assertEquals(1, pipe.sink().write(ByteBuffer.wrap(new byte[] {42})));

            long stagedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((group.pendingCount() != 0 || group.stagedNotificationCount() == 0)
                    && System.nanoTime() < stagedDeadline)
                Thread.sleep(1);

            assertEquals(0, group.pendingCount(),
                    "selector must retire the ready watch even while carriers are saturated");
            assertEquals(1, group.stagedNotificationCount(),
                    "rejected completion must remain staged for a capacity retry");
            assertEquals(1, group.pendingNotifications(),
                    "staged completion must retain exactly one notification reservation");
            assertFalse(ready.isDone(),
                    "Future must not settle before the staged notification is admitted");
            assertEquals(0, callbackCount.get());
            assertFalse(callbackOnSelector.get());
            assertEquals(1, workers.startedWorkerCount(),
                    "selector completion must never grow the saturated carrier pool");

            // Completing the blocker runs the executor capacity hook. The
            // first retry may still see the filler queued; the filler's own
            // completion hook must then retry again and admit the staged NIO
            // notification on the same physical carrier.
            releaseBlocker.countDown();
            assertTrue(fillerRan.await(3, TimeUnit.SECONDS));
            assertTrue((ready.get(5, TimeUnit.SECONDS) & SelectionKey.OP_READ) != 0);
            assertEquals(1, callbackCount.get(),
                    "terminal subscriber must be delivered exactly once");
            assertFalse(callbackOnSelector.get(),
                    "subscriber must never execute on the selector thread");
            assertEquals(1, workers.startedWorkerCount(),
                    "capacity recovery must reuse the existing physical carrier");
            assertEquals(0, group.stagedNotificationCount());
            assertEquals(0, group.pendingNotifications());

            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
        } finally {
            releaseBlocker.countDown();
            group.close();
            pipe.source().close();
            pipe.sink().close();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
