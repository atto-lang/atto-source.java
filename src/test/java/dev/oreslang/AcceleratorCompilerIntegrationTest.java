package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.DataOrientedLayout;
import dev.oreslang.runtime.accel.DopKernelIR;
import dev.oreslang.runtime.accel.QuantumCircuitIR;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AcceleratorCompilerIntegrationTest {
    private static final String GPU = """
        gpu fnc double_and_add(f32 sample): f32 {
            return sample * 2.0 + 1.0;
        }
        """;
    private static final String QUANTUM = """
        quantum fnc bell(): Circuit<2,2> {
            h(0);
            cx(0,1);
            measure(0,0);
            measure(1,1);
        }
        """;

    @Test void trustedCompilerPassesThroughTypedAstAndDeviceProfile() {
        var p = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.AOSOA, 7, 3, 4, 16, 4);
        Ast.GpuF32Decl ast = Parser.parseTrustedGpuDeclaration(GPU, p);
        assertEquals("double_and_add", ast.name());
        assertEquals("sample", ast.scalarParameter());
        assertEquals(Ast.GpuF32Op.SCALE_BIAS, ast.operation());
        assertEquals(2f, ast.scale());
        assertEquals(1f, ast.bias());

        var result = OresCompiler.compileGpuSource(GPU, p,
                new DopKernelIR.GpuTarget(64, 4096, 128, true));
        assertTrue(result.deviceSource().contains("__kernel void oreslang_dop_f32"));
        assertEquals(p.byteLength(), result.requiredBytes());
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileGpuSource(
                GPU, p, new DopKernelIR.GpuTarget(64, 2, 128, true)));
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(GPU));
    }

    @Test void trustedQuantumCompilationPreservesTypedCircuitAndRejectsGuestExecution() {
        Ast.QuantumCircuitDecl ast = Parser.parseTrustedQuantumDeclaration(QUANTUM);
        assertEquals("bell", ast.name());
        assertEquals(2, ast.qubits());
        assertEquals(2, ast.classicalBits());
        assertEquals(4, ast.gates().size());
        assertEquals(Ast.QuantumGateKind.CX, ast.gates().get(1).operation());
        assertThrows(UnsupportedOperationException.class,
                () -> ast.gates().add(new Ast.QuantumGate(Ast.QuantumGateKind.X, 0, -1, -1)));
        QuantumCircuitIR.Circuit circuit = OresCompiler.compileQuantumSource(QUANTUM);
        assertArrayEquals(new double[]{0.5, 0, 0, 0.5},
                QuantumCircuitIR.simulateProbabilities(circuit), 1e-12);
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(QUANTUM));
    }

    @Test void untrustedEffectsRemainForbiddenEvenThroughTrustedCompiler() {
        var layout = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.SOA, 8, 2, 4, 16, 1);
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileGpuSource(
                "gpu fnc x(f32 v): f32 { return await v; }",
                layout, new DopKernelIR.GpuTarget(64, 4096, 128, true)));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileQuantumSource(
                "quantum fnc bad(): Circuit<2,2> { spawn Worker(); measure(0,0); }"));
    }
}
