package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Deterministic admission barriers with concurrent terminal races. */
final class NioReadinessTerminalRaceTest {
    @Test void cancellationAndReadinessNeverLeaveAnOutstandingWatch() throws Exception {
        try (var group = new NioReadinessGroup(1, 32, 32)) {
            for (int round = 0; round < 32; round++) {
                var pipe = Pipe.open();
                try {
                    pipe.source().configureBlocking(false);
                    pipe.sink().configureBlocking(false);
                    var watch = group.watch(pipe.source(), SelectionKey.OP_READ);
                    var start = new CountDownLatch(1);
                    var finished = new CountDownLatch(2);
                    var failure = new AtomicReference<Throwable>();
                    Thread cancel = new Thread(() -> {
                        try {
                            if (!start.await(3, TimeUnit.SECONDS))
                                throw new AssertionError("cancel start timeout");
                            watch.cancel(false);
                        } catch (Throwable t) { failure.compareAndSet(null, t); }
                        finally { finished.countDown(); }
                    }, "nio-cancel-racer");
                    Thread ready = new Thread(() -> {
                        try {
                            if (!start.await(3, TimeUnit.SECONDS))
                                throw new AssertionError("readiness start timeout");
                            pipe.sink().write(ByteBuffer.wrap(new byte[]{1}));
                        } catch (Throwable t) { failure.compareAndSet(null, t); }
                        finally { finished.countDown(); }
                    }, "nio-ready-racer");
                    cancel.start();
                    ready.start();
                    start.countDown();
                    assertTrue(finished.await(5, TimeUnit.SECONDS));
                    cancel.join(3_000L);
                    ready.join(3_000L);
                    assertFalse(cancel.isAlive());
                    assertFalse(ready.isAlive());
                    if (failure.get() != null) fail("terminal racer failed", failure.get());
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while ((group.pendingCount() != 0 || group.pendingNotifications() != 0)
                            && System.nanoTime() < deadline)
                        Thread.sleep(1);
                    assertTrue(watch.isDone(), "race must settle future in round " + round);
                    assertEquals(0, group.pendingCount(), "watch leaked in round " + round);
                    assertEquals(0, group.pendingNotifications(),
                            "notification reservation leaked in round " + round);
                    assertEquals(0, group.stagedNotificationCount(),
                            "staged notification leaked in round " + round);
                } finally {
                    pipe.source().close();
                    pipe.sink().close();
                }
            }
            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
        }
    }

    @Test void closeWithPendingWatchesSettlesAndDrainsNotifications() throws Exception {
        var group = new NioReadinessGroup(1, 32, 32);
        var pipes = new ArrayList<Pipe>();
        try {
            var watches = new ArrayList<OresFuture<Integer>>();
            for (int i = 0; i < 12; i++) {
                var pipe = Pipe.open();
                pipes.add(pipe);
                pipe.source().configureBlocking(false);
                watches.add(group.watch(pipe.source(), SelectionKey.OP_READ));
            }
            assertEquals(12, group.pendingCount());
            group.close();
            assertTrue(group.awaitStopped(5, TimeUnit.SECONDS));
            for (var watch : watches)
                assertTrue(watch.isDone(), "shutdown must settle every admitted watch");
            assertEquals(0, group.pendingCount());
            assertEquals(0, group.pendingNotifications());
            assertEquals(0, group.stagedNotificationCount());
        } finally {
            group.close();
            for (var pipe : pipes) {
                pipe.source().close();
                pipe.sink().close();
            }
        }
    }
}
