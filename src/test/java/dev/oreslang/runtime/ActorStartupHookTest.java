package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Startup runs once on the actor's exclusive lane and gates READY. */
final class ActorStartupHookTest {
    @Test
    void startupCompletesBeforeReadyAndFirstReceive() throws Exception {
        CountDownLatch enteredStartup = new CountDownLatch(1);
        CountDownLatch releaseStartup = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger order = new AtomicInteger();

        class Worker implements ActorRuntime.Behavior<String>,
                ActorRuntime.ActorStartupHook<String> {
            @Override public void onStart(ActorRuntime.ActorContext<String> context)
                    throws Exception {
                starts.incrementAndGet();
                assertEquals(0, order.getAndIncrement());
                enteredStartup.countDown();
                assertTrue(releaseStartup.await(5, TimeUnit.SECONDS));
            }
            @Override public void onMessage(
                    String message, ActorRuntime.ActorContext<String> context) {
                assertEquals("hello", message);
                assertEquals(1, order.getAndIncrement());
                received.countDown();
                context.self().stop();
            }
        }
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawn(Worker::new);
            ref.send("hello");
            assertTrue(enteredStartup.await(5, TimeUnit.SECONDS));
            try {
                assertFalse(ref.ready().isDone(),
                        "READY must remain pending until on_start has completed");
                assertEquals(1, order.get());
            } finally {
                releaseStartup.countDown();
            }
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.ready().isDone());
            assertEquals(1, starts.get());
            assertEquals(2, order.get());
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void failingStartupPreventsAnyReceiveAndReportsTerminalFailure() throws Exception {
        AtomicInteger receives = new AtomicInteger();
        class Broken implements ActorRuntime.Behavior<Integer>,
                ActorRuntime.ActorStartupHook<Integer> {
            @Override public void onStart(ActorRuntime.ActorContext<Integer> context) {
                throw new IllegalStateException("startup failed");
            }
            @Override public void onMessage(
                    Integer message, ActorRuntime.ActorContext<Integer> context) {
                receives.incrementAndGet();
            }
        }
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawn(Broken::new);
            ref.ready();
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, receives.get());
            assertTrue(ref.failure().isPresent());
            var event = runtime.pollSupervisionFailure().orElseThrow();
            assertEquals(SupervisorFailureLane.Kind.HOST_FAILURE, event.kind());
            runtime.acknowledgeSupervisionFailure(event.actor());
        }
    }
}
