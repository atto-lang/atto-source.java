package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Host scheduler checks, not a substitute for .atto guest or NativeAOT evidence. */
@Timeout(20)
final class SuspendsAwaitExecutionContextRuntimeTest {
    @Test
    void foreignProducerCannotAcquireExecutionAuthorityOrRunAwaitInline() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicInteger pc = new AtomicInteger();
            AtomicReference<ExecutionContext> first = new AtomicReference<>();
            AtomicLong firstDispatch = new AtomicLong();
            AtomicReference<Throwable> producerFailure = new AtomicReference<>();
            AtomicReference<Throwable> observerFailure = new AtomicReference<>();
            AtomicInteger observers = new AtomicInteger();
            CountDownLatch observerDone = new CountDownLatch(1);

            OresFuture<Integer> task = scheduler.start(resume -> {
                assertSame(scheduler, OresScheduler.current());
                ExecutionContext current = OresScheduler.currentExecutionContext();
                assertNotNull(current);
                assertSame(current, OresScheduler.currentTaskDomain());
                if (pc.getAndIncrement() == 0) {
                    assertTrue(resume.initial());
                    first.set(current);
                    firstDispatch.set(OresScheduler.currentDispatchId());
                    return OresScheduler.await(gate);
                }
                assertFalse(resume.initial());
                assertSame(first.get(), current);
                assertNotEquals(firstDispatch.get(), OresScheduler.currentDispatchId());
                assertEquals(41, resume.value());
                assertNull(resume.failure());
                return OresScheduler.done(42);
            });

            task.whenCompleteRuntime((value, failure) -> {
                try {
                    assertNull(OresScheduler.current());
                    assertNull(OresScheduler.currentExecutionContext());
                    assertEquals(42, value);
                    assertNull(failure);
                    observers.incrementAndGet();
                } catch (Throwable assertion) {
                    observerFailure.set(assertion);
                } finally {
                    observerDone.countDown();
                }
            });

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (gate.pendingRuntimeWaiterCount() != 1
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(1, gate.pendingRuntimeWaiterCount(),
                    "prove a genuinely suspended continuation before completing it");

            Thread producer = Thread.ofPlatform().name("foreign-future-producer").start(() -> {
                try {
                    assertNull(OresScheduler.current());
                    assertNull(OresScheduler.currentExecutionContext());
                    assertTrue(gate.completeFromRuntime(41));
                    assertNull(OresScheduler.current());
                    assertNull(OresScheduler.currentExecutionContext());
                } catch (Throwable assertion) {
                    producerFailure.set(assertion);
                }
            });
            producer.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(producer.isAlive(), "producer must not execute the guest continuation");
            assertNull(producerFailure.get(), () -> "producer: " + producerFailure.get());
            assertEquals(42, task.get(5, TimeUnit.SECONDS));
            assertTrue(observerDone.await(5, TimeUnit.SECONDS),
                    "terminal callback must drain before checking its assertions");
            assertNull(observerFailure.get(), () -> "observer: " + observerFailure.get());
            assertEquals(1, observers.get());
            assertEquals(2, pc.get());
            assertEquals(0, gate.pendingRuntimeWaiterCount());
        }
    }
}
