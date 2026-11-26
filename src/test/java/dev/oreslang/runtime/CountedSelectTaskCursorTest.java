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

    @Test void actorTrackedCursorStopRevokesRoundAndFencesForeignTask() throws Exception {
        var channel = new ChannelRuntime.Channel<Long>(1);
        var producer = new OresFuture<Long>();
        var armed = new CountDownLatch(1);
        var handle = new AtomicReference<TaskHandle<Integer>>();
        var cursor = new AtomicReference<CountedSelectReadCursor>();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                handle.set(runtime.startTrackedActorTask(resume -> {
                    if (!resume.initial()) throw new AssertionError("stopped actor resumed guest");
                    var c = CountedSelectReadCursor.taskOwnedOrdered(2,
                            List.of(ChannelRuntime.read(channel), ChannelRuntime.await(producer)),
                            ChannelRuntime.SelectPolicy.FAIR);
                    cursor.set(c);
                    var ready = c.beginTaskRound();
                    armed.countDown();
                    return OresScheduler.await(ready);
                }));
            });
            actor.send("start");
            assertTrue(armed.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, cursor.get()::state);
            assertEquals(1, channel.pendingRegistrations());
            assertEquals(1, producer.pendingRuntimeWaiterCount());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, channel.pendingRegistrations());
            assertEquals(0, producer.pendingRuntimeWaiterCount());
            assertFalse(producer.isDone());
            assertTrue(producer.completeFromRuntime(42L));
            assertTrue(channel.tryWrite(7L));
            assertEquals(7L, channel.tryRead().orElseThrow());
            assertInstanceOf(TaskHandle.Cancelled.class, handle.get().getDone().get(5, TimeUnit.SECONDS));
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

    @Test void mixedArmProjectionsFollowLexicalOccurrencesAfterAliasRetirement() throws Exception {
        var a = new ChannelRuntime.Channel<Long>(2);
        var b = new ChannelRuntime.Channel<Long>(1);
        assertTrue(a.tryWrite(1L)); assertTrue(a.tryWrite(4L));
        assertTrue(b.tryWrite(3L));
        var f = OresFuture.completed(2L);
        var aCase = ChannelRuntime.read(a);
        var selection = new AtomicReference<CountedTaskSelection>();
        try (var scheduler = new OresScheduler(2)) {
            var task = scheduler.<List<Long>>startTracked(resume -> {
                if (resume.initial()) {
                    var cursor = new CountedTaskSelection(4,
                            List.of(aCase, ChannelRuntime.await(f), ChannelRuntime.read(b),
                                    aCase, ChannelRuntime.await(f)),
                            ChannelRuntime.SelectPolicy.FAIR,
                            List.of(v -> v + 10, v -> v + 20, v -> v + 30,
                                    v -> v + 40, v -> { throw new AssertionError("retired alias"); }));
                    selection.set(cursor);
                    return OresScheduler.await(cursor.begin());
                }
                var cursor = selection.get();
                if (cursor.retire()) return OresScheduler.done(cursor.result());
                return OresScheduler.await(cursor.begin());
            });
            assertEquals(List.of(11L,22L,33L,44L), task.result().get(5, TimeUnit.SECONDS));
            assertInstanceOf(TaskHandle.Success.class, task.getDone().get(5, TimeUnit.SECONDS));
            assertEquals(0, a.pendingRegistrations());
            assertEquals(0, b.pendingRegistrations());
            assertEquals(0, f.pendingRuntimeWaiterCount());
        }
    }

    @Test void mismatchedArmProjectionsFailBeforeRegistration() throws Exception {
        var channel = new ChannelRuntime.Channel<Long>(1);
        try (var scheduler = new OresScheduler(2)) {
            var task = scheduler.startTracked(resume -> {
                assertThrows(IllegalStateException.class, () -> new CountedTaskSelection(1,
                        List.of(ChannelRuntime.read(channel)), ChannelRuntime.SelectPolicy.FAIR,
                        List.<java.util.function.LongUnaryOperator>of()));
                assertEquals(0, channel.pendingRegistrations());
                return OresScheduler.done(1);
            });
            assertEquals(1, task.result().get(5, TimeUnit.SECONDS));
        }
    }

    @Test void projectionFailureRetiresLosersAndNeverConsumesNextPayload() throws Exception {
        var channel = new ChannelRuntime.Channel<Long>(2);
        assertTrue(channel.tryWrite(10L)); assertTrue(channel.tryWrite(20L));
        var producer = new OresFuture<Long>();
        var selected = new AtomicReference<CountedSelectReadCursor>();
        var error = new AssertionError("projection failure");
        try (var scheduler = new OresScheduler(2)) {
            var task = scheduler.startTracked(resume -> {
                if (resume.initial()) {
                    var cursor = CountedSelectReadCursor.taskOwnedOrdered(2,
                            List.of(ChannelRuntime.read(channel), ChannelRuntime.await(producer)),
                            ChannelRuntime.SelectPolicy.PRIORITY);
                    cursor.setTaskArmProjections(List.of(v -> { throw error; }, v -> v));
                    selected.set(cursor);
                    return OresScheduler.await(cursor.beginTaskRound());
                }
                var cursor = selected.get();
                assertTrue(cursor.retireRound());
                assertEquals(CountedSelectReadCursor.State.FAILED, cursor.state());
                assertSame(error, cursor.failure());
                assertThrows(IllegalStateException.class, cursor::result);
                assertThrows(IllegalStateException.class, cursor::beginTaskRound);
                return OresScheduler.done(42);
            });
            assertEquals(42, task.result().get(5, TimeUnit.SECONDS));
            assertEquals(0, channel.pendingRegistrations());
            assertEquals(0, producer.pendingRuntimeWaiterCount());
            assertFalse(producer.isDone());
            assertEquals(20L, channel.tryRead().orElseThrow());
            assertTrue(producer.completeFromRuntime(22L));
        }
    }

}
