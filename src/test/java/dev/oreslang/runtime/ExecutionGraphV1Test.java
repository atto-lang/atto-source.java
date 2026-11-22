package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** Host-only graph execution conformance; NOT source-level GPU certification. */
final class ExecutionGraphV1Test {
    @Test
    void unitIsSingletonAndTypedCpuGraphExecutes() {
        var unitGraph = new ExecutionGraphV1.Builder();
        assertSame(ExecutionGraphV1.Unit.INSTANCE,
                unitGraph.build(unitGraph.cpuUnit()).executeCpu());

        var graph = new ExecutionGraphV1.Builder();
        var a = graph.cpuI64(21);
        var b = graph.cpuI64(21);
        var sum = graph.cpuAdd(a, b);
        var result = graph.build(sum);
        assertEquals(ExecutionGraphV1.ValueKind.HOST_I64, result.resultType());
        assertEquals(42L, result.executeCpu());
        assertEquals(42L, result.submitCpu(Runnable::run).join());
        assertEquals(3, result.nodes().size());
        assertThrows(UnsupportedOperationException.class,
                () -> result.nodes().clear());
    }

    @Test
    void gpuNodeRequiresExplicitTransfersAndFailsBeforeCpuExecution() {
        var graph = new ExecutionGraphV1.Builder();
        var first = graph.cpuI64(5);
        var second = graph.cpuI64(7);
        var deviceFirst = graph.uploadI64(first);
        var deviceSecond = graph.uploadI64(second);
        var answer = graph.downloadI64(graph.gpuAddI64(deviceFirst, deviceSecond));
        var plan = graph.build(answer);
        assertFalse(plan.cpuOnly());
        assertEquals(ExecutionGraphV1.Domain.GPU, plan.nodes().get(2).domain());
        assertEquals(ExecutionGraphV1.ValueKind.DEVICE_I64,
                plan.nodes().get(2).result());
        var ex = assertThrows(UnsupportedOperationException.class, plan::executeCpu);
        assertTrue(ex.getMessage().contains("no implicit CPU fallback"));
        var queued = new ArrayDeque<Runnable>();
        var future = plan.submitCpu(queued::add);
        assertTrue(queued.isEmpty(), "unsupported GPU work cannot enqueue CPU execution");
        var failed = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(UnsupportedOperationException.class, failed.getCause());
    }

    @Test
    void crossGraphRefsAndSealedBuildersAreRejected() {
        var left = new ExecutionGraphV1.Builder();
        var right = new ExecutionGraphV1.Builder();
        var own = left.cpuI64(1);
        var foreign = right.cpuI64(2);
        assertThrows(IllegalArgumentException.class, () -> left.cpuAdd(own, foreign));
        var plan = left.build(own);
        assertEquals(1L, plan.executeCpu());
        assertThrows(IllegalStateException.class, () -> left.cpuI64(2));
        assertThrows(IllegalStateException.class, () -> left.build(own));
    }

    @Test
    void cpuOverflowFailsWithoutWraparound() {
        var b = new ExecutionGraphV1.Builder();
        var result = b.build(b.cpuAdd(b.cpuI64(Long.MAX_VALUE), b.cpuI64(1)));
        assertThrows(ArithmeticException.class, result::executeCpu);
        assertInstanceOf(ArithmeticException.class,
                assertThrows(CompletionException.class,
                        () -> result.submitCpu(Runnable::run).join()).getCause());
    }

    @Test
    void cancelledQueuedFutureNeverExecutesOrPublishes() {
        var b = new ExecutionGraphV1.Builder();
        var plan = b.build(b.cpuI64(11));
        var queued = new ArrayDeque<Runnable>();
        var future = plan.submitCpu(queued::add);
        assertFalse(future.isDone());
        assertEquals(1, queued.size());
        assertTrue(future.cancel(false));
        queued.removeFirst().run();
        assertTrue(future.isCancelled());
        assertThrows(java.util.concurrent.CancellationException.class, future::join);
    }

    @Test
    void rejectedSubmissionBecomesFailedFutureNotUnexpectedHostFallback() {
        var b = new ExecutionGraphV1.Builder();
        var plan = b.build(b.cpuI64(1));
        var future = plan.submitCpu(job -> {
            throw new RejectedExecutionException("budget full");
        });
        assertInstanceOf(RejectedExecutionException.class,
                assertThrows(CompletionException.class, future::join).getCause());
    }

    @Test
    void duplicateExecutorDispatchHasExactlyOneTerminalPublication() throws Exception {
        var b = new ExecutionGraphV1.Builder();
        var plan = b.build(b.cpuAdd(b.cpuI64(20), b.cpuI64(22)));
        for (int i = 0; i < 128; i++) {
            var queued = new ArrayDeque<Runnable>();
            var future = plan.submitCpu(queued::add);
            var completions = new java.util.concurrent.atomic.AtomicInteger();
            future.whenCompleteRuntime((value, failure) -> completions.incrementAndGet());
            assertEquals(1, queued.size());
            Runnable sameTask = queued.removeFirst();
            var gate = new java.util.concurrent.CountDownLatch(1);
            Runnable concurrent = () -> {
                try {
                    gate.await();
                    sameTask.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            };
            var first = new Thread(concurrent);
            var second = new Thread(concurrent);
            first.start();
            second.start();
            gate.countDown();
            first.join();
            second.join();
            assertEquals(42L, future.join());
            assertEquals(1, completions.get());
        }
    }

    @Test
    void graphBudgetBoundsUntrustedPlanSize() {
        var b = new ExecutionGraphV1.Builder();
        for (int i = 0; i < 4096; i++) b.cpuUnit();
        assertThrows(IllegalArgumentException.class, b::cpuUnit);
    }
}
