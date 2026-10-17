package dev.oreslang;

import dev.oreslang.compiler.ExecutionRegionGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.oreslang.compiler.ExecutionRegionGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure compiler IR contract tests, NOT .ores parser or real GPU execution evidence. */
final class ExecutionRegionGraphTest {
    @Test void cpuScalarActuallyExecutes() {
        var scalar = new CpuRegion(new Add(new I64Literal(40), new I64Literal(2)), false);
        var plan = ExecutionRegionGraph.lower(scalar, Map.of());
        assertEquals(I64, plan.resultType());
        assertEquals(42L, ExecutionRegionGraph.executeCpuI64(plan));
        assertEquals(0, plan.deviceKernels());
        assertEquals(List.of(Effect.HOST), plan.effects());
    }

    @Test void nonblockingRegionsHaveOneFutureResultRatherThanStream() {
        var plan = ExecutionRegionGraph.lower(
                new CpuRegion(new I64Literal(42), true), Map.of());
        assertEquals(Type.future(I64), plan.resultType());
        assertEquals(Opcode.SUBMIT, plan.steps().getLast().opcode());
        assertTrue(plan.effects().contains(Effect.SUSPEND));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionRegionGraph.executeCpuI64(plan));
        var awaited = ExecutionRegionGraph.lower(
                new CpuRegion(new Await(new CpuRegion(new I64Literal(7), true)), false),
                Map.of());
        assertEquals(I64, awaited.resultType());
        assertEquals(Opcode.AWAIT, awaited.steps().getLast().opcode());
    }

    @Test void gpuCpuGpuNestingPlansHostFenceAndDeviceAdmission() {
        var input = new Parameter("device", GPU_F32);
        var nested = new GpuRegion(
                new Sequence(List.of(
                        new DeviceKernel("first", input),
                        new CpuRegion(
                                new Sequence(List.of(
                                        new Download(input),
                                        new GpuRegion(new DeviceKernel("second", input), false)
                                )), false)
                )), false);
        var plan = ExecutionRegionGraph.lower(nested, Map.of("device", GPU_F32));
        assertEquals(GPU_F32, plan.resultType());
        assertEquals(2, plan.deviceKernels());
        assertTrue(plan.effects().contains(Effect.DEVICE));
        assertTrue(plan.effects().contains(Effect.TRANSFER));
        assertTrue(plan.steps().stream().anyMatch(s -> s.opcode() == Opcode.DEVICE_FENCE));
        assertEquals(2, plan.steps().stream()
                .filter(s -> s.opcode() == Opcode.DEVICE_ADMISSION).count());
        for (var s : plan.steps())
            for (int dep : s.dependencies()) assertTrue(dep < s.id(), "cycle not allowed");
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionRegionGraph.executeCpuI64(plan));
    }

    @Test void gpuUnavailableAndInvalidEffectsNeverFallBack() {
        var input = new Parameter("host", HOST_F32);
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionRegionGraph.lower(new GpuRegion(
                        new DeviceKernel("bad", input), false),
                        Map.of("host", HOST_F32)));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionRegionGraph.lower(new GpuRegion(
                        new Add(new I64Literal(1), new I64Literal(2)), false),
                        Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionRegionGraph.lower(
                        new CpuRegion(new Download(input), false),
                        Map.of("host", HOST_F32)));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionRegionGraph.lower(
                        new GpuRegion(new Sequence(List.of()), false), Map.of()));
        var transfer = ExecutionRegionGraph.lower(
                new CpuRegion(new Upload(input), false), Map.of("host", HOST_F32));
        assertEquals(GPU_F32, transfer.resultType());
        assertEquals(Opcode.UPLOAD, transfer.steps().getLast().opcode());
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionRegionGraph.executeCpuI64(transfer));
    }

    @Test void checkedI64OverflowAndDepthBudgets() {
        var overflow = ExecutionRegionGraph.lower(new CpuRegion(
                new Add(new I64Literal(Long.MAX_VALUE), new I64Literal(1)), false),
                Map.of());
        assertThrows(ArithmeticException.class,
                () -> ExecutionRegionGraph.executeCpuI64(overflow));
        Expr nested = new I64Literal(1);
        for (int i = 0; i < 80; ++i) nested = new CpuRegion(nested, false);
        Expr finalNested = nested;
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionRegionGraph.lower(finalNested, Map.of()));
    }
}
