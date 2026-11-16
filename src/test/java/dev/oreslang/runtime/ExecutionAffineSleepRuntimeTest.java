package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Host runtime evidence only: NOT source .atto guest lowering or native parity. */
@Timeout(30)
final class ExecutionAffineSleepRuntimeTest {
    private static void eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(1);
        assertTrue(condition.getAsBoolean(), "expected state not reached within 5s");
    }

    @Test
    void timerSuspendsSameLogicalExecutionWithoutParkingCarrierOrCreatingChildTask() throws Exception {
        try (OresTimers timers = new OresTimers(); OresScheduler scheduler = new OresScheduler(1)) {
            AtomicInteger turns = new AtomicInteger();
            AtomicReference<ExecutionContext> identity = new AtomicReference<>();
            AtomicLong initialDispatch = new AtomicLong();
            CountDownLatch first = new CountDownLatch(1);
            OresFuture<Integer> sleeper = scheduler.start(resume -> {
                ExecutionContext execution = OresScheduler.currentExecutionContext();
                assertNotNull(execution);
                if (turns.getAndIncrement() == 0) {
                    identity.set(execution);
                    initialDispatch.set(OresScheduler.currentDispatchId());
                    first.countDown();
                    return OresScheduler.sleepNanos(timers, TimeUnit.MILLISECONDS.toNanos(250));
                }
                assertSame(identity.get(), execution);
                assertNotEquals(initialDispatch.get(), OresScheduler.currentDispatchId());
                assertSame(scheduler, OresScheduler.current());
                assertNull(resume.failure());
                assertNull(resume.value());
                return OresScheduler.done(42);
            });
            assertTrue(first.await(5, TimeUnit.SECONDS));
            eventually(() -> timers.activeTimerCount() == 1);
            // One carrier can run unrelated work while a logical task sleeps.
            assertEquals(7, scheduler.startSync(() -> 7).get(2, TimeUnit.SECONDS));
            assertEquals(42, sleeper.get(5, TimeUnit.SECONDS));
            assertEquals(2, turns.get());
            assertEquals(0, timers.activeTimerCount());
        }
    }

    @Test
    void zeroDurationMustYieldNextDispatchAndPreserveExecutionAndTrace() throws Exception {
        try (OresTimers timers = new OresTimers(); OresScheduler scheduler = new OresScheduler(1)) {
            TraceContext root = TraceContext.root("0123456789abcdef0123456789abcdef",
                    "0123456789abcdef", true);
            TraceContext before = TraceContext.install(root);
            AtomicReference<ExecutionContext> first = new AtomicReference<>();
            AtomicLong originalDispatch = new AtomicLong();
            AtomicInteger turns = new AtomicInteger();
            OresFuture<Integer> task;
            try {
                task = scheduler.start(resume -> {
                    assertSame(root, TraceContext.current());
                    assertNotNull(OresScheduler.currentExecutionContext());
                    if (turns.getAndIncrement() == 0) {
                        first.set(OresScheduler.currentExecutionContext());
                        originalDispatch.set(OresScheduler.currentDispatchId());
                        return OresScheduler.sleepNanos(timers, 0L);
                    }
                    assertSame(first.get(), OresScheduler.currentExecutionContext());
                    assertNotEquals(originalDispatch.get(), OresScheduler.currentDispatchId());
                    return OresScheduler.done(42);
                });
            } finally {
                TraceContext.install(before);
            }
            assertEquals(42, task.get(5, TimeUnit.SECONDS));
            assertEquals(2, turns.get());
            assertNull(OresScheduler.currentExecutionContext());
            assertNull(TraceContext.current());
        }
    }

    @Test
    void reactorProducerCannotBorrowTaskContextAndNextSleepRetainsOriginalIdentity() throws Exception {
        // A SharedReactorPool completion (like NIO readiness) has no authority
        // to execute the guest state machine or install its ExecutionContext.
        SharedReactorPool.Reactor reactor = new SharedReactorPool.Reactor() {
            @Override public void poll(boolean mayWait) {
                if (mayWait) {
                    try { Thread.sleep(1); } catch (InterruptedException ignored) { }
                }
            }
            @Override public void wake() { }
        };
        try (OresTimers timers = new OresTimers();
             OresScheduler scheduler = new OresScheduler(1);
             SharedReactorPool pool = new SharedReactorPool(reactor, 1, 1)) {
            OresFuture<Integer> readiness = new OresFuture<>();
            AtomicInteger pc = new AtomicInteger();
            AtomicReference<ExecutionContext> original = new AtomicReference<>();
            AtomicLong dispatch = new AtomicLong();
            CountDownLatch awaitingNio = new CountDownLatch(1);
            OresFuture<Integer> task = scheduler.start(resume -> {
                ExecutionContext here = OresScheduler.currentExecutionContext();
                assertNotNull(here);
                switch (pc.getAndIncrement()) {
                    case 0:
                        original.set(here);
                        dispatch.set(OresScheduler.currentDispatchId());
                        awaitingNio.countDown();
                        return OresScheduler.await(readiness);
                    case 1:
                        assertSame(original.get(), here);
                        assertNotEquals(dispatch.get(), OresScheduler.currentDispatchId());
                        assertEquals(41, resume.value());
                        assertNull(resume.failure());
                        return OresScheduler.sleepNanos(timers, TimeUnit.MILLISECONDS.toNanos(1));
                    default:
                        assertSame(original.get(), here);
                        assertNull(resume.failure());
                        return OresScheduler.done(42);
                }
            });
            assertTrue(awaitingNio.await(5, TimeUnit.SECONDS));
            eventually(() -> readiness.pendingRuntimeWaiterCount() == 1);
            CountDownLatch completedOnPool = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            pool.execute(() -> {
                try {
                    assertNull(OresScheduler.currentExecutionContext());
                    assertNull(OresScheduler.current());
                    assertTrue(readiness.completeFromRuntime(41));
                    assertNull(OresScheduler.currentExecutionContext());
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    completedOnPool.countDown();
                }
            });
            assertTrue(completedOnPool.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(42, task.get(5, TimeUnit.SECONDS));
            assertEquals(3, pc.get());
            assertEquals(0, readiness.pendingRuntimeWaiterCount());
            assertEquals(0, timers.activeTimerCount());
        }
    }

    @Test
    void cancellationRevokesOwnedSleepButNotOtherNioFuture() throws Exception {
        try (OresTimers timers = new OresTimers(); OresScheduler scheduler = new OresScheduler(1)) {
            int slots = OresTimers.availableGlobalTimerSlots();
            CountDownLatch started = new CountDownLatch(1);
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                started.countDown();
                return OresScheduler.sleepNanos(timers, TimeUnit.HOURS.toNanos(1));
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            eventually(() -> timers.activeTimerCount() == 1);
            assertTrue(task.cancel("stop sleeping"));
            assertNotNull(task.getDone().get(5, TimeUnit.SECONDS));
            eventually(() -> timers.activeTimerCount() == 0);
            assertEquals(slots, OresTimers.availableGlobalTimerSlots(),
                    "retired sleep may not leak global timer capacity");
        }
    }

    @Test
    void schedulerStopRevokesOwnedTimerAndLeavesSharedReadinessProducerAlive() throws Exception {
        OresFuture<Integer> foreignNio = new OresFuture<>();
        OresTimers timers = new OresTimers();
        OresScheduler scheduler = new OresScheduler(1);
        try {
            AtomicInteger stage = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1);
            OresFuture<Integer> sleeper = scheduler.start(resume -> {
                if (stage.getAndIncrement() == 0) {
                    entered.countDown();
                    return OresScheduler.sleepNanos(timers, TimeUnit.HOURS.toNanos(1));
                }
                return OresScheduler.done(42);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            eventually(() -> timers.activeTimerCount() == 1);
            // A separate task may be waiting for a shared NIO Future. Its
            // scheduler close should detach that waiter, NOT cancel foreignNio.
            OresFuture<Integer> other = scheduler.start(resume -> OresScheduler.await(foreignNio));
            eventually(() -> foreignNio.pendingRuntimeWaiterCount() == 1);
            scheduler.close();
            eventually(() -> timers.activeTimerCount() == 0);
            eventually(() -> foreignNio.pendingRuntimeWaiterCount() == 0);
            assertFalse(foreignNio.isDone(), "scheduler shutdown must not kill shared NIO producer");
            assertTrue(foreignNio.completeFromRuntime(41));
            assertTrue(sleeper.isDone());
            assertTrue(other.isDone());
        } finally {
            scheduler.close();
            timers.close();
        }
    }

    @Test
    void standaloneSleepStepRequiresExecutionContext() {
        try (OresTimers timers = new OresTimers()) {
            assertThrows(IllegalStateException.class,
                    () -> OresScheduler.sleepNanos(timers, 10L));
            assertThrows(IllegalArgumentException.class,
                    () -> new OresScheduler.Sleep<Integer>(timers, -1));
            assertEquals(0, timers.activeTimerCount());
        }
    }
}
