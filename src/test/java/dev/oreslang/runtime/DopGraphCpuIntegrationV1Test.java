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
}
