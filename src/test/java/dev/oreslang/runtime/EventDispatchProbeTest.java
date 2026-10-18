package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Actual instrumented HOST-runtime execution; not general source race coverage. */
@Timeout(30)
public final class EventDispatchProbeTest {
    public static void main(String[] args) throws Exception {
        byte[] input = System.in.readNBytes(256);
        int a = input.length == 0 ? 0 : Byte.toUnsignedInt(input[0]);
        int b = input.length < 2 ? 0 : Byte.toUnsignedInt(input[1]);
        String run = System.getenv("ORESLANG_RACE_RUN_ID");
        var trace = new ArrayList<String>();
        if (run != null) trace.add("{\"op\":\"begin\",\"version\":1,\"run_id\":\"" + run + "\",\"backend\":\"java-host-events\"}");
        if (run == null) exercise(a % 3, 1 + b % 32, (a & 4) != 0, trace);
        else {
            for (int mode = 0; mode < 3; mode++) exercise(mode, 16, false, trace);
            exercise(2, 16, true, trace);
        }
        String destination = System.getenv("ORESLANG_RACE_TRACE");
        if (destination != null && run != null) {
            int events = trace.size() - 1;
            trace.add("{\"op\":\"end\",\"run_id\":\"" + run + "\",\"events\":" + events + "}");
            Files.write(Path.of(destination), trace);
        }
    }

    private static void record(List<String> trace, String op, int id, String fields) {
        trace.add("{\"op\":\"" + op + "\",\"task\":\"turn-" + id + "\"" + fields + "}");
    }

    @Test
    void eventsCannotOvertakeReceiveAcrossCooperation() throws Exception {
        for (int mode = 0; mode < 3; mode++) exercise(mode, 16, false, new ArrayList<>());
    }

    @Test
    void cancellationDoesNotRevokeBlockedCarrier() throws Exception {
        exercise(2, 16, true, new ArrayList<>());
    }

    static void exercise(int mode, int yields, boolean cancel, List<String> trace) throws Exception {
        var dispatch = new AtomicInteger(trace.size());
        var active = new AtomicInteger();
        var exited = new AtomicInteger();
        var current = new ThreadLocal<Integer>();
        var order = new ArrayList<String>();
        var event = new OresFuture<Integer>();
        var entered = new CountDownLatch(1);
        var producerFinished = new CountDownLatch(1);
        var callbackRan = new CountDownLatch(1);
        var pc = new AtomicInteger();
        var lastSource = new AtomicInteger();
        var callbacks = new AtomicInteger();
        ActorRuntime.TurnExecutor executor = turn -> {
            assertEquals(1, active.incrementAndGet(), "overlapping carriers");
            int id = dispatch.incrementAndGet();
            current.set(id);
            record(trace, "task", id, "");
            record(trace, "lock", id, ",\"name\":\"actor-lease\"");
            try { turn.run(); }
            finally {
                record(trace, "unlock", id, ",\"name\":\"actor-lease\"");
                current.remove();
                exited.set(id);
                assertEquals(0, active.decrementAndGet());
            }
        };
        var config = new ActorRuntime.DispatcherConfig(2, 2, 16, 16);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(), config, executor)) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                var task = runtime.startActorTask(resume -> {
                    int state = pc.getAndIncrement();
                    record(trace, "access", current.get(), ",\"location\":\"actor-state\",\"mode\":\"W\"");
                    lastSource.set(current.get());
                    if (state == 0) {
                        order.add("first");
                        if (mode == 0) event.completeFromRuntime(9);
                        runtime.enqueueEventOnCompletion(event, runtime.captureCurrentContinuationTarget(), (value, failure) -> {
                            assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                            assertTrue(exited.get() >= lastSource.get(), "source boundary not exited");
                            assertTrue(current.get() > lastSource.get(), "source dispatch reused");
                            assertNull(failure);
                            assertEquals(9, value);
                            record(trace, "access", current.get(), ",\"location\":\"actor-state\",\"mode\":\"W\"");
                            order.add("third");
                            callbacks.incrementAndGet();
                            callbackRan.countDown();
                        });
                        if (mode == 1) event.completeFromRuntime(9);
                        entered.countDown();
                        if (mode == 2) {
                            try { assertTrue(producerFinished.await(5, TimeUnit.SECONDS)); }
                            catch (InterruptedException failure) { throw new AssertionError(failure); }
                        }
                    }
                    assertEquals(0, callbacks.get(), "event overtook logically active receive");
                    if (state < yields) return OresScheduler.cooperate();
                    order.add("second");
                    return OresScheduler.done(null);
                });
                runtime.suspendCurrentUserMailboxUntil(task);
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("register");
            assertTrue(entered.await(5, TimeUnit.SECONDS), () -> "source failed: " + actor.failure());
            if (mode == 2) {
                assertTrue(event.completeFromRuntime(9));
                if (cancel) {
                    actor.cancel();
                    assertEquals(1, active.get(), "cancellation revoked a blocked carrier");
                }
                producerFinished.countDown();
            }
            if (cancel && mode == 2) {
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertEquals(0, callbacks.get());
            } else {
                assertTrue(callbackRan.await(5, TimeUnit.SECONDS), () -> "event lost: " + actor.failure());
                actor.stop();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(actor.failure().isEmpty(), () -> String.valueOf(actor.failure()));
                assertEquals(List.of("first", "second", "third"), order);
                assertEquals(yields + 1, pc.get());
                assertEquals(1, callbacks.get());
            }
        } finally { producerFinished.countDown(); }
    }

    @Test
    void eventCannotOvertakeGenuinelyPendingSerialReceive() throws Exception {
        var io = new OresFuture<Integer>();
        var waiting = new CountDownLatch(1);
        var delivered = new CountDownLatch(1);
        var order = new ArrayList<String>();
        var callbacks = new AtomicInteger();
        try (var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                var pc = new AtomicInteger();
                var receive = runtime.startActorTask(resume -> {
                    if (pc.getAndIncrement() == 0) {
                        order.add("first");
                        runtime.enqueueEventOnCompletion(OresFuture.completed(9),
                                runtime.captureCurrentContinuationTarget(), (value, failure) -> {
                                    order.add("third");
                                    callbacks.incrementAndGet();
                                    delivered.countDown();
                                });
                        waiting.countDown();
                        return OresScheduler.await(io);
                    }
                    order.add("second");
                    return OresScheduler.done(null);
                });
                runtime.suspendCurrentUserMailboxUntil(receive);
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("pending");
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (io.pendingRuntimeWaiterCount() == 0 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(1, io.pendingRuntimeWaiterCount());
            assertEquals(0, callbacks.get());
            io.completeFromRuntime(1);
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
            assertEquals(List.of("first", "second", "third"), order);
        }
    }

    @Test
    void eventOverflowFailsClosed() throws Exception {
        var attempted = new CountDownLatch(1);
        var callbacks = new AtomicInteger();
        try (var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                var target = runtime.captureCurrentContinuationTarget();
                for (int i = 0; i < 1025; i++) runtime.enqueueEventOnCompletion(
                        OresFuture.completed(i), target, (v, e) -> callbacks.incrementAndGet());
                attempted.countDown();
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("overflow");
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().orElseThrow().getMessage().contains("overflow"));
            assertEquals(0, callbacks.get());
        }
    }

    @Test
    void continuationTargetsCannotCrossRuntimes() throws Exception {
        var target = new AtomicReference<ActorRuntime.ContinuationTarget>();
        var captured = new CountDownLatch(1);
        try (var owner = new ActorRuntime(IsolatePolicy.developer());
             var other = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = owner.<String>spawnShared(() -> (message, context) -> {
                target.set(owner.captureCurrentContinuationTarget());
                captured.countDown();
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("capture");
            assertTrue(captured.await(5, TimeUnit.SECONDS));
            var future = new OresFuture<Integer>();
            assertThrows(IllegalArgumentException.class,
                    () -> other.enqueueOnCompletion(future, target.get(), (v, e) -> fail()));
            assertThrows(IllegalArgumentException.class,
                    () -> other.enqueueEventOnCompletion(future, target.get(), (v, e) -> fail()));
            assertFalse(future.isDone());
            actor.stop();
        }
    }
}
