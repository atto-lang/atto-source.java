package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Real OresScheduler dispatches; NOT an assertion of source compiler instrumentation. */
@Timeout(20)
final class TraceContextSuspensionTest {
    private static final TraceContext ROOT =
            TraceContext.root("11111111111111111111111111111111",
                    "2222222222222222", true);
    private static final TraceContext CHILD = ROOT.child("3333333333333333");

    @Test
    void asyncAwaitRetainsActiveSpanButDoesNotContaminateProducerOrPeer() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> gate = new OresFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);
            AtomicInteger pc = new AtomicInteger();
            AtomicReference<Thread> producerThread = new AtomicReference<>();
            AtomicReference<Throwable> producerFailure = new AtomicReference<>();
            TraceContext prior = TraceContext.install(ROOT);
            OresFuture<Integer> task;
            try {
                task = scheduler.start(resume -> {
                    if (pc.getAndIncrement() == 0) {
                        assertSame(ROOT, TraceContext.current());
                        TraceContext.install(CHILD);
                        suspended.countDown();
                        return OresScheduler.await(gate);
                    }
                    assertSame(CHILD, TraceContext.current(),
                            "resumption must recover the task's child span");
                    assertNotSame(producerThread.get(), Thread.currentThread(),
                            "a producer cannot execute the awaiting continuation");
                    assertEquals(41, resume.value());
                    TraceContext.install(ROOT); // lexical child span exited
                    return OresScheduler.done(42);
                });
            } finally {
                TraceContext.install(prior);
            }
            AtomicReference<TraceContext> subscriberContext = new AtomicReference<>(CHILD);
            CountDownLatch subscriberCalled = new CountDownLatch(1);
            task.whenCompleteRuntime((value, failure) -> {
                subscriberContext.set(TraceContext.current());
                subscriberCalled.countDown();
            });
            assertTrue(suspended.await(5, TimeUnit.SECONDS));
            // The one carrier is reused, but the peer has no inherited span.
            assertNull(scheduler.start(r -> OresScheduler.done(
                    TraceContext.current())).get(5, TimeUnit.SECONDS));
            Thread producer = Thread.ofPlatform().start(() -> {
                try {
                    producerThread.set(Thread.currentThread());
                    assertNull(TraceContext.current(),
                            "Future producer callback must not run with the awaiter's span");
                    gate.completeFromRuntime(41);
                    assertNull(TraceContext.current());
                } catch (Throwable failure) {
                    producerFailure.set(failure);
                }
            });
            producer.join(5_000);
            assertFalse(producer.isAlive());
            assertNull(producerFailure.get(), () -> "producer failed: " + producerFailure.get());
            assertEquals(42, task.get(5, TimeUnit.SECONDS));
            assertTrue(subscriberCalled.await(5, TimeUnit.SECONDS));
            assertNull(subscriberContext.get(),
                    "terminal Future subscribers must not inherit the completed task's span");
            assertEquals(2, pc.get());
            assertNull(scheduler.start(r -> OresScheduler.done(
                    TraceContext.current())).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nestedTaskInheritsActiveSpanAndParentKeepsItAfterAwait() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicInteger pc = new AtomicInteger();
            TraceContext old = TraceContext.install(ROOT);
            OresFuture<TraceContext> parent;
            try {
                parent = scheduler.start(resume -> {
                    if (pc.getAndIncrement() == 0) {
                        TraceContext.install(CHILD);
                        OresFuture<TraceContext> nested = scheduler.start(
                                r -> OresScheduler.done(TraceContext.current()));
                        return OresScheduler.await(nested);
                    }
                    assertSame(CHILD, resume.value());
                    assertSame(CHILD, TraceContext.current());
                    return OresScheduler.done(TraceContext.current());
                });
            } finally {
                TraceContext.install(old);
            }
            assertSame(CHILD, parent.get(5, TimeUnit.SECONDS));
            assertNull(TraceContext.current());
        }
    }

    @Test
    void cooperateAndThrownTaskRestoreCarrierState() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicInteger pc = new AtomicInteger();
            TraceContext old = TraceContext.install(ROOT);
            OresFuture<TraceContext> cooperating;
            try {
                cooperating = scheduler.start(resume -> {
                    if (pc.getAndIncrement() == 0) {
                        TraceContext.install(CHILD);
                        return OresScheduler.cooperate();
                    }
                    assertSame(CHILD, TraceContext.current());
                    return OresScheduler.done(TraceContext.current());
                });
            } finally {
                TraceContext.install(old);
            }
            assertSame(CHILD, cooperating.get(5, TimeUnit.SECONDS));
            OresFuture<Integer> failing;
            old = TraceContext.install(ROOT);
            try {
                failing = scheduler.start(resume -> {
                    assertSame(ROOT, TraceContext.current());
                    throw new IllegalStateException("synthetic failure");
                });
            } finally {
                TraceContext.install(old);
            }
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class,
                            () -> failing.get(5, TimeUnit.SECONDS)).getCause());
            assertNull(scheduler.start(r -> OresScheduler.done(
                    TraceContext.current())).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationOfSuspendedTaskNeverLeaksItsActiveSpan() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> pending = new OresFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);
            TraceContext old = TraceContext.install(ROOT);
            OresFuture<Integer> task;
            try {
                task = scheduler.start(resume -> {
                    TraceContext.install(CHILD);
                    suspended.countDown();
                    return OresScheduler.await(pending);
                });
            } finally {
                TraceContext.install(old);
            }
            assertTrue(suspended.await(5, TimeUnit.SECONDS));
            assertTrue(task.cancel(false));
            assertNull(scheduler.start(r -> OresScheduler.done(
                    TraceContext.current())).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsNonCanonicalOrAllZeroTraceIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> TraceContext.root("0".repeat(32), "2222222222222222", true));
        assertThrows(IllegalArgumentException.class,
                () -> TraceContext.root("1".repeat(32), "0".repeat(16), true));
        assertThrows(IllegalArgumentException.class,
                () -> TraceContext.root("A".repeat(32), "2222222222222222", true));
        assertThrows(IllegalArgumentException.class, () -> ROOT.child("short"));
        assertThrows(IllegalArgumentException.class, () -> ROOT.child(ROOT.spanId()));
        assertFalse(TraceContext.root("1".repeat(32), "2".repeat(16), false).sampled());
        assertEquals(ROOT.traceId(), CHILD.traceId());
        assertNotEquals(ROOT.spanId(), CHILD.spanId());
    }
}
