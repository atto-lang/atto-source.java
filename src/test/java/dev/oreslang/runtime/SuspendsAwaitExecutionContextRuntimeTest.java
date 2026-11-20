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

    @Test
    void nestedTasksHaveDistinctExecutionIdsAndAuthenticatedParentLineage() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            AtomicReference<ExecutionContext> parentContext = new AtomicReference<>();
            AtomicReference<ExecutionContext> childContext = new AtomicReference<>();
            AtomicReference<ExecutionContext> grandchildContext = new AtomicReference<>();
            AtomicLong firstParentDispatch = new AtomicLong();
            AtomicLong firstChildDispatch = new AtomicLong();
            AtomicInteger parentPc = new AtomicInteger();
            AtomicInteger childPc = new AtomicInteger();

            OresFuture<Integer> parent = scheduler.start(resume -> {
                ExecutionContext current = OresScheduler.currentExecutionContext();
                assertNotNull(current);
                if (parentPc.getAndIncrement() == 0) {
                    assertEquals(0L, current.parentExecution(), "root Task has no parent");
                    parentContext.set(current);
                    firstParentDispatch.set(OresScheduler.currentDispatchId());
                    OresFuture<Integer> child = scheduler.start(childResume -> {
                        ExecutionContext childCurrent = OresScheduler.currentExecutionContext();
                        assertNotNull(childCurrent);
                        if (childPc.getAndIncrement() == 0) {
                            childContext.set(childCurrent);
                            assertEquals(parentContext.get().id().execution(),
                                    childCurrent.parentExecution());
                            firstChildDispatch.set(OresScheduler.currentDispatchId());
                            OresFuture<Integer> grandchild = scheduler.start(grandchildResume -> {
                                ExecutionContext grandchildCurrent =
                                        OresScheduler.currentExecutionContext();
                                grandchildContext.set(grandchildCurrent);
                                assertEquals(childCurrent.id().execution(),
                                        grandchildCurrent.parentExecution());
                                assertEquals(ExecutionContext.AllocationKind.NONE,
                                        grandchildCurrent.allocationDomain().kind());
                                return OresScheduler.done(40);
                            });
                            return OresScheduler.await(grandchild);
                        }
                        assertSame(childContext.get(), childCurrent,
                                "child resume must retain original logical identity");
                        assertNotEquals(firstChildDispatch.get(),
                                OresScheduler.currentDispatchId());
                        return OresScheduler.done(((Integer) childResume.value()) + 1);
                    });
                    return OresScheduler.await(child);
                }
                assertSame(parentContext.get(), current,
                        "parent resume must retain original logical identity");
                assertNotEquals(firstParentDispatch.get(),
                        OresScheduler.currentDispatchId());
                return OresScheduler.done(((Integer) resume.value()) + 1);
            });

            assertEquals(42, parent.get(5, TimeUnit.SECONDS));
            ExecutionContext root = parentContext.get();
            ExecutionContext child = childContext.get();
            ExecutionContext grandchild = grandchildContext.get();
            assertNotNull(root);
            assertNotNull(child);
            assertNotNull(grandchild);
            assertNotEquals(root.id(), child.id());
            assertNotEquals(child.id(), grandchild.id());
            assertEquals(root.id().runtimeDomain(), grandchild.id().runtimeDomain());
            assertEquals(root.id().execution(), child.parentExecution());
            assertEquals(child.id().execution(), grandchild.parentExecution());
            assertEquals(0L, root.parentExecution());
            assertEquals(ExecutionContext.AllocationKind.NONE,
                    child.allocationDomain().kind(),
                    "Task ancestry must never mint an actor or heap capability");
            assertEquals(0L, child.continuation(),
                    "causal Task parentage must not forge a compiler-lowered frame");
        }
    }

}
