package dev.oreslang.runtime;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class CountedSelectTaskCursorTest {
    @Test void schedulerIdentitySurvivesResumesAndRejectsForeignTask() throws Exception {
        var channel = new ChannelRuntime.Channel<Long>(2);
        var producer = new OresFuture<Long>();
        var armed = new CountDownLatch(1);
        var cursor = new AtomicReference<CountedSelectReadCursor>();
        var domain = new AtomicReference<Object>();
        try (var scheduler = new OresScheduler(2)) {
            var task = scheduler.<List<Long>>startTracked(resume -> {
                if (resume.initial()) {
                    domain.set(OresScheduler.currentTaskDomain());
                    var c = CountedSelectReadCursor.taskOwnedOrdered(3,
                            List.of(ChannelRuntime.read(channel), ChannelRuntime.await(producer)),
                            ChannelRuntime.SelectPolicy.FAIR);
                    cursor.set(c);
                    var ready = c.beginTaskRound();
                    armed.countDown();
                    return OresScheduler.await(ready);
                }
                assertSame(domain.get(), OresScheduler.currentTaskDomain());
                var c = cursor.get();
                assertTrue(c.retireRound());
                if (c.state() == CountedSelectReadCursor.State.SUCCESS)
                    return OresScheduler.done(c.result());
                return OresScheduler.await(c.beginTaskRound());
            });
            assertTrue(armed.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, cursor.get()::state);
            var foreign = scheduler.startTracked(resume -> {
                assertThrows(IllegalStateException.class, cursor.get()::state);
                return OresScheduler.done(1);
            });
            assertEquals(1, foreign.result().get(5, TimeUnit.SECONDS));
            assertTrue(channel.tryWrite(10L));
            assertTrue(producer.completeFromRuntime(20L));
            assertTrue(channel.tryWrite(30L));
            assertEquals(List.of(10L,20L,30L), task.result().get(5, TimeUnit.SECONDS));
            assertInstanceOf(TaskHandle.Success.class, task.getDone().get(5, TimeUnit.SECONDS));
            assertEquals(0, channel.pendingRegistrations());
            assertEquals(0, producer.pendingRuntimeWaiterCount());
        }
    }

    @Test void abandonedTrackedTaskRevokesRoundBeforeDoneWithoutCancellingProducer() throws Exception {
        var channel = new ChannelRuntime.Channel<Long>(1);
        var producer = new OresFuture<Long>();
        var armed = new CountDownLatch(1);
        try (var scheduler = new OresScheduler(2)) {
            var task = scheduler.startTracked(resume -> {
                if (!resume.initial()) throw new AssertionError("cancelled task resumed");
                var c = CountedSelectReadCursor.taskOwnedOrdered(2,
                        List.of(ChannelRuntime.read(channel), ChannelRuntime.await(producer)),
                        ChannelRuntime.SelectPolicy.FAIR);
                var ready = c.beginTaskRound();
                assertFalse(ready.cancel(false), "receipt must be observation only");
                armed.countDown();
                return OresScheduler.await(ready);
            });
            assertTrue(armed.await(5, TimeUnit.SECONDS));
            assertEquals(1, channel.pendingRegistrations());
            assertEquals(1, producer.pendingRuntimeWaiterCount());
            assertTrue(task.cancel());
            assertInstanceOf(TaskHandle.Cancelled.class, task.getDone().get(5, TimeUnit.SECONDS));
            assertEquals(0, channel.pendingRegistrations());
            assertEquals(0, producer.pendingRuntimeWaiterCount());
            assertFalse(producer.isDone());
            assertTrue(channel.tryWrite(42L));
            assertEquals(42L, channel.tryRead().orElseThrow());
        }
    }
}
