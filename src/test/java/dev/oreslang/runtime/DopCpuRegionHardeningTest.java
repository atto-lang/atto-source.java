package dev.oreslang.runtime;

import dev.oreslang.runtime.accel.DopKernelIR;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Adversarial, deterministic CPU DOP admission and field-range geometry checks. */
final class DopCpuRegionHardeningTest {
    private static DataOrientedLayout.Plan plan(DataOrientedLayout.Kind kind) {
        return new DataOrientedLayout.Plan(kind, 7, 3, 4, 16,
                kind == DataOrientedLayout.Kind.AOSOA ? 4 : 1);
    }
    private static DopKernelIR.Kernel kernel(DataOrientedLayout.Plan shape) {
        return new DopKernelIR.Kernel(shape, DopKernelIR.Element.F32,
                DopKernelIR.Op.SCALE_BIAS, 2f, 1f);
    }

    @Test
    void exhaustiveHalfOpenRangesPreserveEveryUntouchedCell() {
        for (var kind : DataOrientedLayout.Kind.values()) {
            var shape = plan(kind);
            for (int field = 0; field < 3; field++)
                for (int begin = 0; begin <= 7; begin++)
                    for (int end = begin; end <= 7; end++) {
                        var input = new DopKernelIR.HostBuffer(shape);
                        var output = new DopKernelIR.HostBuffer(shape);
                        for (int row = 0; row < 7; row++)
                            for (int col = 0; col < 3; col++) {
                                input.write(row, col, row * 3f + col);
                                output.write(row, col, -100f);
                            }
                        var src = DopRegionSchedule.Access.rows(input.regionRoot(),
                                field, begin, end - begin, DopRegionSchedule.Mode.READ);
                        var dst = DopRegionSchedule.Access.rows(output.regionRoot(),
                                field, begin, end - begin, DopRegionSchedule.Mode.WRITE);
                        DopKernelIR.executeCpuRegion(kernel(shape), input, output, src, dst);
                        for (int row = 0; row < 7; row++)
                            for (int col = 0; col < 3; col++) {
                                float expected = row >= begin && row < end && col == field
                                        ? (row * 3f + col) * 2f + 1f : -100f;
                                assertEquals(expected, output.read(row, col),
                                        "unexpected write in " + kind + " field " + field
                                                + " range " + begin + ".." + end);
                            }
                        input.close();
                        output.close();
                    }
        }
    }

    @Test
    void allReadersFailDuringExclusiveWriteAcrossThreads() throws Exception {
        var shape = plan(DataOrientedLayout.Kind.AOSOA);
        for (int trial = 0; trial < 32; trial++) {
            var buffer = new DopKernelIR.HostBuffer(shape);
            var writer = buffer.borrowWrite();
            var ready = new CountDownLatch(6);
            var start = new CountDownLatch(1);
            var rejected = new AtomicInteger();
            var threads = new java.util.ArrayList<Thread>();
            try {
                for (int i = 0; i < 6; i++) {
                    var reader = new Thread(() -> {
                        ready.countDown();
                        try {
                            if (!start.await(5, TimeUnit.SECONDS)) return;
                            try {
                                buffer.read(0, 0);
                            } catch (IllegalStateException good) {
                                rejected.incrementAndGet();
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    reader.start();
                    threads.add(reader);
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                for (var thread : threads) {
                    thread.join(5000);
                    assertFalse(thread.isAlive());
                }
                assertEquals(6, rejected.get(), "reader admitted during exclusive CPU output");
                assertThrows(IllegalStateException.class, buffer::take);
                assertThrows(IllegalStateException.class, buffer::close);
            } finally {
                start.countDown();
                for (var thread : threads) thread.join(5000);
                writer.close();
            }
            buffer.write(0, 0, 4f);
            assertEquals(4f, buffer.read(0, 0));
            buffer.close();
        }
    }

    @Test
    void retirementAfterPreflightNeverGrantsStaleExecutionAccess() {
        var shape = plan(DataOrientedLayout.Kind.SOA);
        var input = new DopKernelIR.HostBuffer(shape);
        var output = new DopKernelIR.HostBuffer(shape);
        var src = DopRegionSchedule.Access.rows(input.regionRoot(), 0, 1, 2,
                DopRegionSchedule.Mode.READ);
        var dst = DopRegionSchedule.Access.rows(output.regionRoot(), 0, 1, 2,
                DopRegionSchedule.Mode.WRITE);
        var task = new DopRegionSchedule.Task("preflight", List.of(src, dst));
        DopRegionSchedule.requireLiveOwned(task);
        var moved = output.take();
        DopRegionSchedule.requireLiveOwned(task);
        moved.close();
        assertFalse(dst.root().isLiveOwned());
        assertThrows(IllegalStateException.class, () -> DopRegionSchedule.requireLiveOwned(task));
        assertThrows(IllegalStateException.class,
                () -> DopKernelIR.executeCpuRegion(kernel(shape), input, output, src, dst));
        input.close();
    }
}
