package dev.oreslang;

import dev.oreslang.compiler.ExecutionRegionGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.oreslang.compiler.ExecutionRegionGraph.*;
import static org.junit.jupiter.api.Assertions.*;

final class ExecutionRegionGraphTaskV2Test {
    @Test void synchronousCpuRemainsAPlainValue() {
        var plan = ExecutionRegionGraph.lower(
                new CpuRegion(new Add(new I64Literal(40), new I64Literal(2)), LaunchMode.SYNC),
                Map.of());
        assertEquals(I64, plan.resultType());
        assertEquals(0, plan.taskSubmissions());
        assertFalse(plan.effects().contains(Effect.SUBMIT));
        assertEquals(42L, ExecutionRegionGraph.executeCpuI64(plan));
    }

    @Test void taskSubmissionPrecedesTaskBodyAndDoesNotItselfSuspendTheWaiter() {
        var plan = ExecutionRegionGraph.lower(
                new CpuRegion(new Add(new I64Literal(20), new I64Literal(22)), LaunchMode.TASK),
                Map.of());
        assertEquals(Type.future(I64), plan.resultType());
        assertEquals(1, plan.taskSubmissions());
        assertEquals(Opcode.TASK_SUBMIT, plan.steps().getFirst().opcode());
        assertEquals(Opcode.TASK_COMPLETE, plan.steps().getLast().opcode());
        assertTrue(plan.effects().contains(Effect.SUBMIT));
        assertFalse(plan.effects().contains(Effect.SUSPEND),
                "CPU task creation alone is not await/suspension");
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionRegionGraph.executeCpuI64(plan));
    }

    @Test void legacyNbConstructorMapsToTaskMode() {
        var legacy = ExecutionRegionGraph.lower(
                new CpuRegion(new I64Literal(7), true), Map.of());
        var canonical = ExecutionRegionGraph.lower(
                new CpuRegion(new I64Literal(7), LaunchMode.TASK), Map.of());
        assertEquals(legacy.resultType(), canonical.resultType());
        assertEquals(legacy.taskSubmissions(), canonical.taskSubmissions());
        assertEquals(Opcode.TASK_SUBMIT, legacy.steps().getFirst().opcode());
    }

    @Test void awaitIsTheWaiterSuspensionBoundary() {
        var awaited = ExecutionRegionGraph.lower(
                new Await(new CpuRegion(new I64Literal(7), LaunchMode.TASK)),
                Map.of());
        assertEquals(I64, awaited.resultType());
        assertTrue(awaited.effects().contains(Effect.SUBMIT));
        assertTrue(awaited.effects().contains(Effect.SUSPEND));
        assertEquals(Opcode.AWAIT, awaited.steps().getLast().opcode());
    }

    @Test void gpuTaskHasSubmissionBeforeDeviceAdmissionAndNeverCpuFallsBack() {
        var device = new Parameter("device", GPU_F32);
        var plan = ExecutionRegionGraph.lower(
                new GpuRegion(new DeviceKernel("kernel", device), LaunchMode.TASK),
                Map.of("device", GPU_F32));
        assertEquals(Type.future(GPU_F32), plan.resultType());
        assertEquals(Opcode.TASK_SUBMIT, plan.steps().getFirst().opcode());
        int submit = plan.steps().getFirst().id();
        int admission = plan.steps().stream()
                .filter(step -> step.opcode() == Opcode.DEVICE_ADMISSION)
                .findFirst().orElseThrow().id();
        assertTrue(submit < admission);
        assertTrue(plan.effects().containsAll(List.of(
                Effect.SUBMIT, Effect.DEVICE, Effect.SUSPEND)));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionRegionGraph.executeCpuI64(plan));
    }

    @Test void gpuRequiresRealDeviceWorkAndNestedFutureIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionRegionGraph.lower(
                        new GpuRegion(new Sequence(List.of()), LaunchMode.TASK), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionRegionGraph.lower(
                        new CpuRegion(
                                new CpuRegion(new I64Literal(1), LaunchMode.TASK),
                                LaunchMode.TASK),
                        Map.of()));
    }
}
