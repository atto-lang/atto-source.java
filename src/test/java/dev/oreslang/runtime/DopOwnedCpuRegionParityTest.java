package dev.oreslang.runtime;

import dev.oreslang.runtime.accel.DopKernelIR;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class DopOwnedCpuRegionParityTest {
    private static DataOrientedLayout.Plan plan(DataOrientedLayout.Kind kind) {
        return new DataOrientedLayout.Plan(kind, 7, 3, 4, 16,
                kind == DataOrientedLayout.Kind.AOSOA ? 4 : 1);
    }

    private static DopKernelIR.Kernel kernel(DataOrientedLayout.Plan layout) {
        return new DopKernelIR.Kernel(layout, DopKernelIR.Element.F32,
                DopKernelIR.Op.SCALE_BIAS, 2.0f, 1.0f);
    }

    @Test
    void physicalIdentityIsStableAcrossTakeAndRetiredOnClose() {
        for (var kind : DataOrientedLayout.Kind.values()) {
            var owner = new DopKernelIR.HostBuffer(plan(kind));
            var separate = new DopKernelIR.HostBuffer(plan(kind));
            var root = owner.regionRoot();
            var copy = owner.regionRoot();
            var unrelated = separate.regionRoot();
            var synthetic = DopRegionSchedule.Root.trustedCreate(7, 3);

            assertTrue(root.sameRoot(copy));
            assertFalse(root.sameRoot(unrelated));
            assertFalse(root.sameRoot(synthetic));
            assertFalse(synthetic.isLiveOwned());
            assertTrue(root.isLiveOwned());
            var valid = new DopRegionSchedule.Task("valid",
                    List.of(DopRegionSchedule.Access.rows(root, 1, 0, 7,
                            DopRegionSchedule.Mode.WRITE)));
            assertDoesNotThrow(() -> DopRegionSchedule.requireLiveOwned(valid));
            assertThrows(IllegalStateException.class,
                    () -> DopRegionSchedule.requireLiveOwned(new DopRegionSchedule.Task(
                            "forged", List.of(DopRegionSchedule.Access.rows(synthetic, 1, 0, 7,
                                    DopRegionSchedule.Mode.READ)))));
            assertFalse(DopRegionSchedule.independent(
                    DopRegionSchedule.Access.rows(root, 0, 1, 3, DopRegionSchedule.Mode.READ),
                    DopRegionSchedule.Access.rows(copy, 0, 1, 3, DopRegionSchedule.Mode.WRITE)),
                    "distinct root wrappers for the same storage must still alias");

            var successor = owner.take();
            assertThrows(IllegalStateException.class, owner::regionRoot);
            assertTrue(root.sameRoot(successor.regionRoot()));
            assertDoesNotThrow(() -> DopRegionSchedule.requireLiveOwned(valid));
            owner.close(); // moved-from wrapper must not retire successor storage
            assertTrue(root.isLiveOwned());
            successor.close();
            assertFalse(root.isLiveOwned());
            assertThrows(IllegalStateException.class, () -> DopRegionSchedule.requireLiveOwned(valid));
            separate.close();
            assertFalse(unrelated.isLiveOwned());
        }
    }

    @Test
    void checkedCpuSlicesPreserveUntouchedCellsInAllLayouts() {
        for (var kind : DataOrientedLayout.Kind.values()) {
            var layout = plan(kind);
            var input = new DopKernelIR.HostBuffer(layout);
            var output = new DopKernelIR.HostBuffer(layout);
            for (int row = 0; row < 7; row++)
                for (int field = 0; field < 3; field++)
                    input.write(row, field, row * 10 + field);
            var src = DopRegionSchedule.Access.rows(input.regionRoot(), 1, 2, 3,
                    DopRegionSchedule.Mode.READ);
            var dst = DopRegionSchedule.Access.rows(output.regionRoot(), 1, 2, 3,
                    DopRegionSchedule.Mode.WRITE);
            DopKernelIR.executeCpuRegion(kernel(layout), input, output, src, dst);
            for (int row = 0; row < 7; row++)
                for (int field = 0; field < 3; field++) {
                    var value = row >= 2 && row < 5 && field == 1
                            ? 2f * (row * 10 + field) + 1f : 0f;
                    assertEquals(value, output.read(row, field));
                }
            assertThrows(IllegalArgumentException.class,
                    () -> DopKernelIR.executeCpuRegion(kernel(layout), input, output,
                            DopRegionSchedule.Access.rows(
                                    DopRegionSchedule.Root.trustedCreate(7, 3), 1, 2, 3,
                                    DopRegionSchedule.Mode.READ), dst));
            assertThrows(IllegalArgumentException.class,
                    () -> DopKernelIR.executeCpuRegion(kernel(layout), input, output,
                            DopRegionSchedule.Access.rows(input.regionRoot(), 1, 2, 3,
                                    DopRegionSchedule.Mode.WRITE), dst));
            assertThrows(IllegalArgumentException.class,
                    () -> DopKernelIR.executeCpuRegion(kernel(layout), input, output, src,
                            DopRegionSchedule.Access.rows(output.regionRoot(), 2, 2, 3,
                                    DopRegionSchedule.Mode.WRITE)));
            assertThrows(IllegalArgumentException.class,
                    () -> DopKernelIR.executeCpuRegion(kernel(layout), input, output, src,
                            DopRegionSchedule.Access.rows(output.regionRoot(), 1, 3, 2,
                                    DopRegionSchedule.Mode.WRITE)));
            assertThrows(IllegalArgumentException.class,
                    () -> DopKernelIR.executeCpuRegion(kernel(layout), input, input, src,
                            DopRegionSchedule.Access.rows(input.regionRoot(), 1, 2, 3,
                                    DopRegionSchedule.Mode.WRITE)));
            var successor = input.take();
            DopKernelIR.executeCpuRegion(kernel(layout), successor, output, src, dst);
            output.close();
            assertFalse(dst.root().isLiveOwned());
            assertThrows(IllegalStateException.class,
                    () -> DopKernelIR.executeCpuRegion(kernel(layout), successor, output, src, dst));
            successor.close();
        }
    }

    @Test
    void occupiedDestinationRejectsWithoutPartialWriteOrLeakedSourceLoan() {
        var layout = plan(DataOrientedLayout.Kind.SOA);
        var input = new DopKernelIR.HostBuffer(layout);
        var output = new DopKernelIR.HostBuffer(layout);
        input.write(2, 1, 11f);
        output.write(2, 1, 101f);
        var src = DopRegionSchedule.Access.rows(input.regionRoot(), 1, 2, 3,
                DopRegionSchedule.Mode.READ);
        var dst = DopRegionSchedule.Access.rows(output.regionRoot(), 1, 2, 3,
                DopRegionSchedule.Mode.WRITE);
        try (var reserved = output.borrowRead()) {
            assertThrows(IllegalStateException.class,
                    () -> DopKernelIR.executeCpuRegion(kernel(layout), input, output, src, dst));
            assertEquals(101f, reserved.read(2, 1));
        }
        var successor = input.take();
        assertEquals(101f, output.read(2, 1));
        DopKernelIR.executeCpuRegion(kernel(layout), successor, output, src, dst);
        assertEquals(23f, output.read(2, 1));
        successor.close();
        output.close();
    }

    @Test
    void exclusiveWriteLoanBlocksReadersAndTransferAcrossThreads() throws Exception {
        var layout = plan(DataOrientedLayout.Kind.AOSOA);
        var output = new DopKernelIR.HostBuffer(layout);
        var writer = output.borrowWrite();
        var entered = new CountDownLatch(1);
        var rejected = new AtomicBoolean();
        try {
            writer.write(0, 0, 19f);
            assertThrows(IllegalStateException.class, output::take);
            assertThrows(IllegalStateException.class, output::close);
            assertThrows(IllegalStateException.class, output::borrowRead);
            var reader = new Thread(() -> {
                entered.countDown();
                try { output.read(0, 0); }
                catch (IllegalStateException expected) { rejected.set(true); }
            });
            reader.start();
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            reader.join(5000);
            assertFalse(reader.isAlive(), "concurrent reader did not finish");
            assertTrue(rejected.get(), "writer exposed an incomplete output region");
        } finally {
            writer.close();
        }
        assertEquals(19f, output.read(0, 0));
        assertThrows(IllegalStateException.class, () -> writer.write(0, 0, 5f));
        output.close();
    }
}
