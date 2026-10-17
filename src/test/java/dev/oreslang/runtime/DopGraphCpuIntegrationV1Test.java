package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.runtime.accel.DopKernelIR;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** Host integration tests: not proof of Oreslang source-level cpu/gpu lowering. */
final class DopGraphCpuIntegrationV1Test {
    private static final DataOrientedLayout.Plan LAYOUT =
            new DataOrientedLayout.Plan(DataOrientedLayout.Kind.SOA, 2, 1,
                    Float.BYTES, Float.BYTES, 1);

    private static ExecutionGraphV1.Plan<Long> scalar() {
        var builder = new ExecutionGraphV1.Builder();
        return builder.build(builder.cpuAdd(builder.cpuI64(20), builder.cpuI64(22)));
    }

    private static DopKernelIR.Kernel kernel() {
        return new DopKernelIR.Kernel(LAYOUT, DopKernelIR.Element.F32,
                DopKernelIR.Op.SCALE_BIAS, 2.0f, 1.0f);
    }

    private static DopRegionSchedule.Access region(DopKernelIR.HostBuffer buffer,
                                                   DopRegionSchedule.Mode privilege) {
        return DopRegionSchedule.Access.rows(buffer.regionRoot(), 0, 0, 2, privilege);
    }

    @Test
    void ownedKernelCompletesBeforeTypedGraphWithoutImplicitStreaming() {
        try (var input = new DopKernelIR.HostBuffer(LAYOUT);
             var output = new DopKernelIR.HostBuffer(LAYOUT)) {
            input.write(0, 0, 3.0f);
            input.write(1, 0, 5.0f);
            var source = region(input, DopRegionSchedule.Mode.READ);
            var destination = region(output, DopRegionSchedule.Mode.WRITE);
            var dependency = DopRegionSchedule.dependencyLevels(List.of(
                    new DopRegionSchedule.Task("kernel", List.of(source, destination)),
                    new DopRegionSchedule.Task("reader", List.of(
                            region(output, DopRegionSchedule.Mode.READ)))));
            assertEquals(List.of(List.of(0), List.of(1)), dependency);

            var jobs = new ArrayDeque<Runnable>();
            OresFuture<Long> future = DopGraphCpuIntegrationV1.submit(
                    kernel(), input, output, source, destination, scalar(), jobs::add);
            assertEquals(1, jobs.size());
            assertFalse(future.isDone());
            assertEquals(0.0f, output.read(0, 0));
            Runnable worker = jobs.removeFirst();
            worker.run();
            assertEquals(42L, future.join());
            assertEquals(7.0f, output.read(0, 0));
            assertEquals(11.0f, output.read(1, 0));
            try (var borrow = output.borrowRead()) {
                worker.run(); // duplicate scheduler dispatch must be inert
                assertEquals(7.0f, borrow.read(0, 0));
            }
            assertEquals(42L, future.join());
        }
    }

    @Test
    void cancellationBeforeWorkerClaimCannotMutateBuffer() {
        try (var input = new DopKernelIR.HostBuffer(LAYOUT);
             var output = new DopKernelIR.HostBuffer(LAYOUT)) {
            input.write(0, 0, 3.0f);
            var jobs = new ArrayDeque<Runnable>();
            var future = DopGraphCpuIntegrationV1.submit(kernel(), input, output,
                    region(input, DopRegionSchedule.Mode.READ),
                    region(output, DopRegionSchedule.Mode.WRITE),
                    scalar(), jobs::add);
            assertTrue(future.cancel(false));
            jobs.removeFirst().run();
            assertTrue(future.isCancelled());
            assertEquals(0.0f, output.read(0, 0));
        }
    }

    @Test
    void gpuGraphRejectedBeforeSchedulerAndBufferMutation() {
        try (var input = new DopKernelIR.HostBuffer(LAYOUT);
             var output = new DopKernelIR.HostBuffer(LAYOUT)) {
            var b = new ExecutionGraphV1.Builder();
            var gpu = b.build(b.downloadI64(b.uploadI64(b.cpuI64(7))));
            var jobs = new ArrayDeque<Runnable>();
            var f = DopGraphCpuIntegrationV1.submit(kernel(), input, output,
                    region(input, DopRegionSchedule.Mode.READ),
                    region(output, DopRegionSchedule.Mode.WRITE),
                    gpu, jobs::add);
            assertTrue(f.isDone());
            assertTrue(jobs.isEmpty());
            assertInstanceOf(UnsupportedOperationException.class,
                    assertThrows(CompletionException.class, f::join).getCause());
            assertEquals(0.0f, output.read(0, 0));
        }
    }

    @Test
    void invalidOwnedFootprintRejectedWithoutScheduling() {
        try (var input = new DopKernelIR.HostBuffer(LAYOUT);
             var output = new DopKernelIR.HostBuffer(LAYOUT)) {
            var jobs = new ArrayDeque<Runnable>();
            var bad = DopGraphCpuIntegrationV1.submit(kernel(), input, output,
                    region(input, DopRegionSchedule.Mode.WRITE),
                    region(output, DopRegionSchedule.Mode.WRITE),
                    scalar(), jobs::add);
            assertTrue(bad.isDone());
            assertTrue(jobs.isEmpty());
            assertInstanceOf(IllegalArgumentException.class,
                    assertThrows(CompletionException.class, bad::join).getCause());
        }
    }

    @Test
    void failedExclusiveBorrowDoesNotPartiallyWriteOrLeakInputLoan() {
        try (var input = new DopKernelIR.HostBuffer(LAYOUT);
             var output = new DopKernelIR.HostBuffer(LAYOUT)) {
            input.write(0, 0, 3.0f);
            var jobs = new ArrayDeque<Runnable>();
            var result = DopGraphCpuIntegrationV1.submit(kernel(), input, output,
                    region(input, DopRegionSchedule.Mode.READ),
                    region(output, DopRegionSchedule.Mode.WRITE),
                    scalar(), jobs::add);
            try (var existing = output.borrowRead()) {
                jobs.removeFirst().run();
                assertInstanceOf(IllegalStateException.class,
                        assertThrows(CompletionException.class, result::join).getCause());
                assertEquals(0.0f, existing.read(0, 0));
            }
            // Failed output loan admission must also release the input loan.
            input.write(0, 0, 4.0f);
            assertEquals(4.0f, input.read(0, 0));
        }
    }

    @Test
    void inlineExecutorFailsBeforeTouchingOwnedStorage() {
        try (var input = new DopKernelIR.HostBuffer(LAYOUT);
             var output = new DopKernelIR.HostBuffer(LAYOUT)) {
            input.write(0, 0, 3.0f);
            var result = DopGraphCpuIntegrationV1.submit(kernel(), input, output,
                    region(input, DopRegionSchedule.Mode.READ),
                    region(output, DopRegionSchedule.Mode.WRITE),
                    scalar(), Runnable::run);
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(CompletionException.class, result::join).getCause());
            assertEquals(0.0f, output.read(0, 0));
        }
    }

    @Test
    void inlineThenThrowReportsSchedulerRejectionNotInlineError() {
        try (var input = new DopKernelIR.HostBuffer(LAYOUT);
             var output = new DopKernelIR.HostBuffer(LAYOUT)) {
            input.write(0, 0, 3.0f);
            var failed = DopGraphCpuIntegrationV1.submit(kernel(), input, output,
                    region(input, DopRegionSchedule.Mode.READ),
                    region(output, DopRegionSchedule.Mode.WRITE),
                    scalar(), worker -> {
                        worker.run();
                        throw new RejectedExecutionException("inline then rejected");
                    });
            var cause = assertThrows(CompletionException.class, failed::join).getCause();
            assertInstanceOf(RejectedExecutionException.class, cause);
            assertEquals("inline then rejected", cause.getMessage());
            assertEquals(0.0f, output.read(0, 0));
        }
    }

    @Test
    void startedWorkerCannotWriteIfSchedulerRejectsAfterEnqueue() throws Exception {
        // Executor can start another carrier before its admission returns.
        // The queued worker must wait until the scheduler commits admission,
        // never modifying the output when execute() subsequently throws.
        for (int round = 0; round < 64; round++) {
            try (var input = new DopKernelIR.HostBuffer(LAYOUT);
                 var output = new DopKernelIR.HostBuffer(LAYOUT)) {
                input.write(0, 0, 3.0f);
                var launched = new java.util.concurrent.CountDownLatch(1);
                var running = new java.util.concurrent.atomic.AtomicReference<Thread>();
                var result = DopGraphCpuIntegrationV1.submit(kernel(), input, output,
                        region(input, DopRegionSchedule.Mode.READ),
                        region(output, DopRegionSchedule.Mode.WRITE),
                        scalar(), job -> {
                            var worker = new Thread(() -> {
                                launched.countDown();
                                job.run();
                            });
                            running.set(worker);
                            worker.start();
                            try {
                                assertTrue(launched.await(5, java.util.concurrent.TimeUnit.SECONDS));
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new RejectedExecutionException(ex);
                            }
                            throw new RejectedExecutionException("threw after starting worker");
                        });
                var worker = running.get();
                assertNotNull(worker);
                worker.join(5000);
                assertFalse(worker.isAlive(), "scheduler rejection stranded CPU worker");
                assertInstanceOf(RejectedExecutionException.class,
                        assertThrows(CompletionException.class, result::join).getCause());
                assertEquals(0.0f, output.read(0, 0));
            }
        }
    }

    @Test
    void executorThatEnqueuesThenThrowsCannotMutateOutput() {
        try (var input = new DopKernelIR.HostBuffer(LAYOUT);
             var output = new DopKernelIR.HostBuffer(LAYOUT)) {
            var jobs = new ArrayDeque<Runnable>();
            var result = DopGraphCpuIntegrationV1.submit(kernel(), input, output,
                    region(input, DopRegionSchedule.Mode.READ),
                    region(output, DopRegionSchedule.Mode.WRITE),
                    scalar(), job -> {
                        jobs.add(job);
                        throw new RejectedExecutionException("capacity lost");
                    });
            assertEquals(1, jobs.size());
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(CompletionException.class, result::join).getCause());
            jobs.removeFirst().run();
            assertEquals(0.0f, output.read(0, 0));
        }
    }

    @Test
    void zeroRowOwnedCpuGraphCompletesWithoutAccessingStorage() {
        for (var kind : DataOrientedLayout.Kind.values()) {
            var layout = new DataOrientedLayout.Plan(kind, 0, 3,
                    Float.BYTES, 16, kind == DataOrientedLayout.Kind.AOSOA ? 4 : 1);
            assertEquals(0L, layout.byteLength());
            try (var input = new DopKernelIR.HostBuffer(layout);
                 var output = new DopKernelIR.HostBuffer(layout)) {
                var source = DopRegionSchedule.Access.rows(
                        input.regionRoot(), 2, 0, 0, DopRegionSchedule.Mode.READ);
                var destination = DopRegionSchedule.Access.rows(
                        output.regionRoot(), 2, 0, 0, DopRegionSchedule.Mode.WRITE);
                var jobs = new ArrayDeque<Runnable>();
                var cpu = new DopKernelIR.Kernel(layout, DopKernelIR.Element.F32,
                        DopKernelIR.Op.SCALE_BIAS, 2.0f, 1.0f);
                var result = DopGraphCpuIntegrationV1.submit(
                        cpu, input, output, source, destination, scalar(), jobs::add);
                assertFalse(result.isDone(), "zero-row work still needs worker admission");
                assertEquals(1, jobs.size());
                jobs.removeFirst().run();
                assertEquals(42L, result.join(), "independent host graph must still run");
                assertThrows(IndexOutOfBoundsException.class, () -> output.read(0, 2));
                assertTrue(destination.root().isLiveOwned());
            }
        }
    }

    @Test
    void emptySliceOfPopulatedBufferDoesNotTouchOtherRows() {
        for (var kind : DataOrientedLayout.Kind.values()) {
            var layout = new DataOrientedLayout.Plan(kind, 3, 2,
                    Float.BYTES, 16, kind == DataOrientedLayout.Kind.AOSOA ? 4 : 1);
            try (var input = new DopKernelIR.HostBuffer(layout);
                 var output = new DopKernelIR.HostBuffer(layout)) {
                input.write(1, 0, 7.0f);
                output.write(1, 0, 19.0f);
                var source = DopRegionSchedule.Access.rows(
                        input.regionRoot(), 0, 1, 0, DopRegionSchedule.Mode.READ);
                var destination = DopRegionSchedule.Access.rows(
                        output.regionRoot(), 0, 1, 0, DopRegionSchedule.Mode.WRITE);
                var jobs = new ArrayDeque<Runnable>();
                var cpu = new DopKernelIR.Kernel(layout, DopKernelIR.Element.F32,
                        DopKernelIR.Op.SCALE_BIAS, 2.0f, 1.0f);
                var result = DopGraphCpuIntegrationV1.submit(
                        cpu, input, output, source, destination, scalar(), jobs::add);
                assertEquals(1, jobs.size());
                jobs.removeFirst().run();
                assertEquals(42L, result.join());
                assertEquals(19.0f, output.read(1, 0), "empty slice must not write");
                assertEquals(0.0f, output.read(0, 0));
                assertEquals(0.0f, output.read(2, 0));
            }
        }
    }

}
