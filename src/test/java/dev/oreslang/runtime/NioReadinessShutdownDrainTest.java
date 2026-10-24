package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class NioReadinessShutdownDrainTest {
    @Test void closeDoesNotPublishQuiescenceBeforeStagedNotificationDrains()
            throws Exception {
        var group = new NioReadinessGroup(1, 8, 8);
        var pipe = Pipe.open();
        var accepting = new AtomicBoolean();
        var callbackCount = new AtomicInteger();
        var callbackOnSelector = new AtomicBoolean();
        try {
            group.bindVmCompletionExecutor(command -> {
                if (!accepting.get())
                    throw new RejectedExecutionException("synthetic saturation");
                command.run();
            }, false);

            pipe.source().configureBlocking(false);
            pipe.sink().configureBlocking(false);
            var ready = group.watch(pipe.source(), SelectionKey.OP_READ);
            ready.whenCompleteRuntime((events, failure) -> {
                if (NioReadinessGroup.onReactorThread())
                    callbackOnSelector.set(true);
                callbackCount.incrementAndGet();
            });
            assertEquals(1, pipe.sink().write(ByteBuffer.wrap(new byte[] {1})));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((group.pendingCount() != 0 || group.stagedNotificationCount() == 0)
                    && System.nanoTime() < deadline)
                Thread.sleep(1);

            assertEquals(0, group.pendingCount());
            assertEquals(1, group.stagedNotificationCount());
            assertEquals(1, group.pendingNotifications());
            assertFalse(ready.isDone());

            group.close();
            assertFalse(group.awaitStopped(150, TimeUnit.MILLISECONDS),
                    "shutdown must wait for retained terminal notification");

            accepting.set(true);
            group.retryStagedNotifications();

            assertTrue((ready.get(3, TimeUnit.SECONDS) & SelectionKey.OP_READ) != 0);
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
            assertEquals(1, callbackCount.get());
            assertFalse(callbackOnSelector.get());
            assertEquals(0, group.pendingNotifications());
            assertEquals(0, group.stagedNotificationCount());
        } finally {
            accepting.set(true);
            group.retryStagedNotifications();
            group.close();
            pipe.source().close();
            pipe.sink().close();
        }
    }
}
