package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExecutionRegionGraph;
import dev.oreslang.compiler.ExecutionRegionGraph.Add;
import dev.oreslang.compiler.ExecutionRegionGraph.CpuRegion;
import dev.oreslang.compiler.ExecutionRegionGraph.DeviceKernel;
import dev.oreslang.compiler.ExecutionRegionGraph.Expr;
import dev.oreslang.compiler.ExecutionRegionGraph.GpuRegion;
import dev.oreslang.compiler.ExecutionRegionGraph.I64Literal;
import dev.oreslang.compiler.ExecutionRegionGraph.LaunchMode;
import dev.oreslang.compiler.ExecutionRegionGraph.Opcode;
import dev.oreslang.compiler.ExecutionRegionGraph.Parameter;
import dev.oreslang.compiler.ExecutionRegionGraph.Plan;
import dev.oreslang.compiler.ExecutionRegionGraph.Sequence;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.accel.QuantumCircuitIR;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.AbstractList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static dev.oreslang.compiler.ExecutionRegionGraph.GPU_F32;
import static dev.oreslang.compiler.ExecutionRegionGraph.HOST_F32;
import static dev.oreslang.compiler.ExecutionRegionGraph.I64;
import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for the cpu/gpu/quantum execution-domain audit. */
final class ExecutionDomainHardeningTest {
    private static DeviceKernel kernel(String name, String buffer) {
        return new DeviceKernel(name, new Parameter(buffer, GPU_F32));
    }

    private static List<Opcode> opcodes(Plan plan) {
        return plan.steps().stream().map(ExecutionRegionGraph.Step::opcode).toList();
    }

    @Test
    void hostResidentParametersCannotEnterGpuRegion() {
        var hostScalar = new GpuRegion(new Sequence(List.of(
                new Parameter("n", I64), kernel("k", "g"))), LaunchMode.SYNC);
        var error = assertThrows(IllegalArgumentException.class,
                () -> ExecutionRegionGraph.lower(hostScalar, Map.of("g", GPU_F32, "n", I64)));
        assertTrue(error.getMessage().contains("host-resident parameter"), error::getMessage);

        var hostBufferTail = new GpuRegion(new Sequence(List.of(
                kernel("k", "g"), new Parameter("h", HOST_F32))), LaunchMode.SYNC);
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionRegionGraph.lower(hostBufferTail, Map.of("g", GPU_F32, "h", HOST_F32)));

        // Device buffers remain legal kernel inputs.
        assertDoesNotThrow(() -> ExecutionRegionGraph.lower(
                new GpuRegion(kernel("k", "g"), LaunchMode.SYNC), Map.of("g", GPU_F32)));
    }

    @Test
    void synchronousGpuRegionFencesBeforeHostContinues() {
        var root = new CpuRegion(new Sequence(List.of(
                new GpuRegion(kernel("k", "g"), LaunchMode.SYNC),
                new Add(new I64Literal(1), new I64Literal(2)))), LaunchMode.SYNC);
        List<Opcode> ops = opcodes(ExecutionRegionGraph.lower(root, Map.of("g", GPU_F32)));
        int kernelAt = ops.indexOf(Opcode.DEVICE_KERNEL);
        int fenceAt = ops.indexOf(Opcode.DEVICE_FENCE);
        int hostAt = ops.indexOf(Opcode.CONST_I64);
        assertTrue(kernelAt >= 0 && kernelAt < fenceAt && fenceAt < hostAt, ops::toString);
    }

    @Test
    void deviceWorkAfterHostContinuationIsReadmitted() {
        var root = new GpuRegion(new Sequence(List.of(
                kernel("k1", "g1"),
                new CpuRegion(new I64Literal(1), LaunchMode.SYNC),
                kernel("k2", "g2"))), LaunchMode.SYNC);
        List<Opcode> ops = opcodes(ExecutionRegionGraph.lower(root, Map.of("g1", GPU_F32, "g2", GPU_F32)));
        int hostAt = ops.indexOf(Opcode.CONST_I64);
        int secondKernelAt = ops.lastIndexOf(Opcode.DEVICE_KERNEL);
        int readmissionAt = ops.subList(hostAt, ops.size()).indexOf(Opcode.DEVICE_ADMISSION) + hostAt;
        assertTrue(readmissionAt > hostAt && readmissionAt < secondKernelAt, ops::toString);
    }

    @Test
    void sharedSubtreeDagIsRejectedByTraversalBudget() {
        Expr shared = new I64Literal(1);
        for (int level = 0; level < 40; level++) shared = new Sequence(List.of(shared, shared));
        Expr root = new CpuRegion(shared, LaunchMode.SYNC);
        var error = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(
                IllegalArgumentException.class, () -> ExecutionRegionGraph.lower(root, Map.of())));
        assertTrue(error.getMessage().contains("traversal budget"), error::getMessage);
    }

    @Test
    void quantumGateQuotaRejectsUnderstatedIteratorBeforeUnboundedCopy() {
        var x = new QuantumCircuitIR.Gate(QuantumCircuitIR.Op.X, 0, -1, -1);
        var measure = new QuantumCircuitIR.Gate(QuantumCircuitIR.Op.MEASURE, 0, -1, 0);
        final int[] consumed = {0};
        List<QuantumCircuitIR.Gate> understated = new AbstractList<>() {
            @Override public int size() { return 1; }
            @Override public QuantumCircuitIR.Gate get(int index) { return measure; }
            @Override public java.util.Iterator<QuantumCircuitIR.Gate> iterator() {
                return new java.util.Iterator<>() {
                    private int cursor;
                    @Override public boolean hasNext() { return cursor < 5000; }
                    @Override public QuantumCircuitIR.Gate next() {
                        consumed[0]++;
                        return ++cursor == 5000 ? measure : x;
                    }
                };
            }
            @Override public Object[] toArray() {
                throw new AssertionError("untrusted toArray must never be called");
            }
        };
        var error = assertThrows(IllegalArgumentException.class,
                () -> new QuantumCircuitIR.Circuit(1, 1, understated));
        assertTrue(error.getMessage().contains("gate quota"), error::getMessage);
        assertEquals(1024, consumed[0], "must stop before consuming a 1,025th gate");
    }

    @Test
    void quantumGateSnapshotDoesNotInvokeUntrustedToArray() {
        var measure = new QuantumCircuitIR.Gate(QuantumCircuitIR.Op.MEASURE, 0, -1, 0);
        List<QuantumCircuitIR.Gate> input = new AbstractList<>() {
            @Override public int size() { return 1; }
            @Override public QuantumCircuitIR.Gate get(int index) {
                if (index != 0) throw new IndexOutOfBoundsException(index);
                return measure;
            }
            @Override public Object[] toArray() {
                throw new AssertionError("untrusted toArray must never be called");
            }
        };
        assertEquals(List.of(measure), new QuantumCircuitIR.Circuit(1, 1, input).gates());
    }

    @Test
    void gpuTaskRequiresTheIsolateGpuCapability() {
        Ast.Program program = Parser.parse(
                "pub fnc main(): int { let f = gpu task { 1 }; return 0; }");
        var withoutGpu = new IsolatePolicy(EnumSet.noneOf(IsolatePolicy.Capability.class),
                64L << 20, 16, Duration.ofSeconds(1));
        var error = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, withoutGpu));
        assertTrue(error.getMessage().contains("GPU"), error::getMessage);

        var withGpu = new IsolatePolicy(EnumSet.of(IsolatePolicy.Capability.GPU),
                64L << 20, 16, Duration.ofSeconds(1));
        assertDoesNotThrow(() -> CapabilityChecker.check(program, withGpu));
        // A CPU task never needs the device capability.
        assertDoesNotThrow(() -> CapabilityChecker.check(Parser.parse(
                "pub fnc main(): int { let f = cpu task { 1 }; return 0; }"), withoutGpu));
    }
}
