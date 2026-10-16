package dev.oreslang.runtime.accel;

import dev.oreslang.runtime.DataOrientedLayout;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class DopCpuWriteLoanIsolationTest {
    private static DataOrientedLayout.Plan plan(DataOrientedLayout.Kind kind) {
        return new DataOrientedLayout.Plan(kind, 7, 3, 4, 16,
                kind == DataOrientedLayout.Kind.AOSOA ? 4 : 1);
    }

    @Test
    void outputWriteLoanExcludesReadersWritersAndTransfersAcrossThreads() throws Exception {
        for (var kind : DataOrientedLayout.Kind.values()) {
            var output = new DopKernelIR.HostBuffer(plan(kind));
            var writer = output.borrowWrite();
            try {
                writer.write(0, 0, 19.0f);
                assertThrows(IllegalStateException.class, () -> output.read(0, 0));
                assertThrows(IllegalStateException.class, output::borrowRead);
                assertThrows(IllegalStateException.class, output::borrowWrite);
                assertThrows(IllegalStateException.class, () -> output.write(1, 1, 99.0f));
                assertThrows(IllegalStateException.class, output::take);
                assertThrows(IllegalStateException.class, output::close);
                var start = new CountDownLatch(1);
                var rejected = new AtomicBoolean();
                var reader = new Thread(() -> {
                    try {
                        start.await();
                        output.read(0, 0);
                    } catch (IllegalStateException expected) {
                        rejected.set(true);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                reader.start();
                start.countDown();
                reader.join();
                assertTrue(rejected.get(), "concurrent reader observed partially written output");
            } finally {
                writer.close();
            }
            assertEquals(19.0f, output.read(0, 0));
            assertThrows(IllegalStateException.class, () -> writer.write(0, 0, 1.0f));
            assertThrows(IllegalStateException.class, writer::close);
            try (var reader = output.borrowRead()) {
                assertThrows(IllegalStateException.class, output::borrowWrite);
            }
            output.close();
        }
    }

    @Test
    void failedExclusiveKernelAdmissionNeverModifiesDestinationOrLeaksInputLoan() {
        var shape = plan(DataOrientedLayout.Kind.SOA);
        var kernel = new DopKernelIR.Kernel(shape, DopKernelIR.Element.F32,
                DopKernelIR.Op.SCALE_BIAS, 2.0f, 1.0f);
        var input = new DopKernelIR.HostBuffer(shape);
        var output = new DopKernelIR.HostBuffer(shape);
        input.write(0, 0, 7.0f);
        output.write(0, 0, 101.0f);
        try (var writer = output.borrowWrite()) {
            assertThrows(IllegalStateException.class,
                    () -> DopKernelIR.executeCpu(kernel, input, output));
            assertThrows(IllegalStateException.class, () -> output.read(0, 0));
            writer.write(0, 1, 55.0f);
        }
        // The failed acquisition must have released its input read loan.
        var transferred = input.take();
        transferred.close();
        assertEquals(101.0f, output.read(0, 0));
        output.close();
    }

    @Test
    void cpuKernelRemainsCorrectForAllPhysicalLayouts() {
        for (var kind : DataOrientedLayout.Kind.values()) {
            var shape = plan(kind);
            var kernel = new DopKernelIR.Kernel(shape, DopKernelIR.Element.F32,
                    DopKernelIR.Op.SCALE_BIAS, 2.0f, 1.0f);
            var input = new DopKernelIR.HostBuffer(shape);
            var output = new DopKernelIR.HostBuffer(shape);
            for (int row = 0; row < 7; row++) {
                for (int field = 0; field < 3; field++) {
                    input.write(row, field, row * 3 + field);
                }
            }
            DopKernelIR.executeCpu(kernel, input, output);
            for (int row = 0; row < 7; row++) {
                for (int field = 0; field < 3; field++) {
                    assertEquals(2.0f * (row * 3 + field) + 1.0f, output.read(row, field));
                }
            }
            input.close();
            output.close();
        }
    }
}
