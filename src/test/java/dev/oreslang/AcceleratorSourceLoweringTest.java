package dev.oreslang;

import dev.oreslang.runtime.DataOrientedLayout;
import dev.oreslang.runtime.accel.AcceleratorSourceLowering;
import dev.oreslang.runtime.accel.DopKernelIR;
import dev.oreslang.runtime.accel.QuantumCircuitIR;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AcceleratorSourceLoweringTest {
    private static DataOrientedLayout.Plan layout() {
        return new DataOrientedLayout.Plan(DataOrientedLayout.Kind.AOSOA, 7, 3, 4, 16, 4);
    }

    @Test void gpuSourceLowersIntoExecutableCpuIRAndExplicitDeviceSource() {
        String code = """
                gpu fnc transform(f32 value): f32 {
                    return value * 2.0 + 1.0;
                }
                """;
        var program = AcceleratorSourceLowering.compileGpu(code, layout());
        assertEquals("transform", program.name());
        assertEquals(DopKernelIR.Op.SCALE_BIAS, program.kernel().operation());
        assertEquals(2f, program.kernel().scale());
        assertEquals(1f, program.kernel().bias());
        try (var input = new DopKernelIR.HostBuffer(layout());
             var result = new DopKernelIR.HostBuffer(layout())) {
            input.write(5, 2, 3f);
            DopKernelIR.executeCpu(program.kernel(), input, result);
            assertEquals(7f, result.read(5, 2));
        }
        var artifact = program.emitOpenCl(new DopKernelIR.GpuTarget(64, 4096, 64, true));
        assertTrue(artifact.deviceSource().contains("__kernel void oreslang_dop_f32"));
        assertEquals(layout().byteLength(), artifact.requiredBytes());
        assertThrows(IllegalArgumentException.class,
                () -> program.emitOpenCl(new DopKernelIR.GpuTarget(64, 1, 64, true)));
        // Main language evaluator must still refuse hardware blocks until a real
        // capability checked execution path is connected.
        assertThrows(RuntimeException.class, () -> Parser.parse(code));
    }

    @Test void gpuGrammarIsStrictAndNoHostEffectsEscape() {
        for (String body : new String[]{
                "return value * 2.0; stdio.println(1);",
                "return value * 2.0 + 1.0 * 3.0;",
                "return unsafe_host(value);",
                "return await value;",
                "return value / 2.0;",
                "return value * 3e400;",
                "return value * 2.0; spawn Worker();"
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                AcceleratorSourceLowering.compileGpu(
                        "gpu fnc k(f32 value): f32 { " + body + " }", layout()), body);
        }
        var identity = AcceleratorSourceLowering.compileGpu(
                "gpu fnc scale(f32 value): f32 { return value * -2.0; }", layout());
        assertEquals(-2f, identity.kernel().scale());
        var minus = AcceleratorSourceLowering.compileGpu(
                "gpu fnc bias(f32 value): f32 { return value - 3.0; }", layout());
        assertEquals(-3f, minus.kernel().bias());
    }

    @Test void rejectsHugeInputsAndUnderflowWhilePreservingSubnormalF32() {
        var p = layout();
        var small = AcceleratorSourceLowering.compileGpu(
                "gpu fnc tiny(f32 value): f32 { return value * 1e-45; }", p);
        assertEquals(Float.MIN_VALUE, small.kernel().scale());
        assertThrows(IllegalArgumentException.class, () ->
                AcceleratorSourceLowering.compileGpu(
                        "gpu fnc tiny(f32 value): f32 { return value * 1e-1000; }", p));
        assertThrows(IllegalArgumentException.class, () ->
                AcceleratorSourceLowering.compileGpu(
                        "gpu fnc huge(f32 value): f32 { return value; }" + " ".repeat(70_000), p));
        assertThrows(IllegalArgumentException.class, () ->
                AcceleratorSourceLowering.compileQuantum(
                        "quantum fnc huge(): Circuit<2,2> { measure(0,0); }" + " ".repeat(70_000)));
    }

    @Test void quantumSourceLowersToSimulatorAndOpenQasmWithoutFallback() {
        String code = """
                quantum fnc bell(): Circuit<2,2> {
                    h(0);
                    cx(0, 1);
                    measure(0,0);
                    measure(1,1);
                }
                """;
        var program = AcceleratorSourceLowering.compileQuantum(code);
        assertEquals("bell", program.name());
        assertTrue(program.emitOpenQasm3().contains("cx q[0], q[1];"));
        assertArrayEquals(new double[]{.5, 0, 0, .5},
                QuantumCircuitIR.simulateProbabilities(program.circuit()), 1e-12);
        assertThrows(RuntimeException.class, () -> Parser.parse(code));
    }

    @Test void quantumSourceRejectsHostEffectsAndInvalidCircuits() {
        for (String body : new String[]{
                "h(0); spawn Worker(); measure(0,0);",
                "x(0); await foo(); measure(0,0);",
                "measure(0,0); h(1);",
                "cx(0,0); measure(0,0);",
                "measure(0,0); measure(0,1);",
                "print(0); measure(0,0);",
                "h(0);",
                "measure(3,0);",
                "measure(0,0); abc();"
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                AcceleratorSourceLowering.compileQuantum(
                        "quantum fnc f(): Circuit<2,2> { " + body + " }"), body);
        }
    }
}
