package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuKernelSourceHirV1;
import dev.oreslang.compiler.GpuKernelTailGuardV1;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonical GPU surface syntax and fail-closed source typing only.
 * No source plan or accepted parser case grants physical GPU execution.
 */
final class GpuKernelCanonicalSyntaxV1Test {
    private static final String CANONICAL = """
            pub gpu fnc saxpy(
                f32 alpha,
                GpuArray<f32> x,
                GpuArray<f32> y,
                GpuArray<f32> mut out,
                u64 n
            ) -> void {
                const u64 i = gpu.global_id(0);
                if i >= n { return; }
                out[i] = alpha * x[i] + y[i];
            }
            """;

    private static Ast.GpuKernelDecl parse(String source) {
        Ast.Program program = Parser.parse(source);
        return assertInstanceOf(Ast.GpuKernelDecl.class,
                program.modules().getFirst().declarations().getFirst());
    }

    private static GpuKernelSourceHirV1.Plan lower(String source) {
        return GpuKernelSourceHirV1.lower(parse(source));
    }

    private static void errorContains(String source, String diagnostic) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> lower(source));
        assertTrue(failure.getMessage().contains(diagnostic), failure::getMessage);
    }

    @Test void canonicalSignatureUsesNarrowArrowAndExplicitMutableReferent() {
        var fn = parse(CANONICAL).signature();
        assertEquals("saxpy", fn.name());
        assertEquals("void", fn.returnType().name());
        assertEquals("u64", fn.parameters().get(4).type().name());
        assertEquals("GpuArray", fn.parameters().get(3).type().name());
        assertFalse(fn.parameters().get(1).mutable());
        assertFalse(fn.parameters().get(2).mutable());
        assertTrue(fn.parameters().get(3).mutable());
    }

    @Test void typedUnsignedIndexAndCapabilityMapRemainNotExecutable() {
        var plan = lower(CANONICAL);
        assertEquals(GpuKernelSourceHirV1.Type.U64, plan.parameters().get(4).type());
        assertEquals(GpuKernelSourceHirV1.Type.BUFFER_READ_F32, plan.parameters().get(1).type());
        assertEquals(GpuKernelSourceHirV1.Type.BUFFER_READ_F32, plan.parameters().get(2).type());
        assertEquals(GpuKernelSourceHirV1.Type.BUFFER_READ_WRITE_F32,
                plan.parameters().get(3).type());
        assertEquals(GpuKernelSourceHirV1.Type.U64, plan.body().getFirst().value().type());
        assertEquals(3, plan.body().size());
        assertEquals("RETURN_VOID", plan.body().get(1).body().getFirst().opcode());
        assertEquals("STORE_F32", plan.body().get(2).opcode());
        assertEquals(3L, GpuKernelTailGuardV1.audit(plan).inspectedMemoryOps());
        assertFalse(plan.executable());
    }

    @Test void fatArrowIsNotADeviceSignature() {
        var failure = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse(CANONICAL.replace(") -> void", ") => void")));
        assertTrue(failure.getMessage().contains("GPU-KERNEL-001"), failure::getMessage);
    }

    @Test void colonReturnTypeIsNotCanonicalGpuSyntax() {
        var failure = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse(CANONICAL.replace(") -> void", "): void")));
        assertTrue(failure.getMessage().contains("GPU-KERNEL-001"), failure::getMessage);
    }

    @Test void unmarkedGpuArrayOutputCannotBeWritten() {
        errorContains(CANONICAL.replace("GpuArray<f32> mut out", "GpuArray<f32> out"),
                "GPU-KERNEL-005");
    }

    @Test void aMutableScalarIsNotAnOwnedGpuOutputView() {
        errorContains(CANONICAL.replace("f32 alpha", "f32 mut alpha"),
                "GPU-KERNEL-005");
    }

    @Test void unsignedLaneCannotCompareWithSignedBound() {
        errorContains(CANONICAL.replace("u64 n", "i64 n"), "GPU-KERNEL-002");
    }

    @Test void unsignedLaneCannotBeSilentlyAssignedToSignedLocal() {
        errorContains(CANONICAL.replace("const u64 i", "const int i"), "GPU-KERNEL-002");
    }

    @Test void unsupportedGpuArrayElementTypeIsRejected() {
        errorContains(CANONICAL.replace("GpuArray<f32> x", "GpuArray<i64> x"),
                "GPU-KERNEL-002");
    }

    @Test void wrongIndexVariableIsNotCoveredByCanonicalTailGuard() {
        errorContains(CANONICAL.replace("out[i] =", "out[n] ="), "GPU-KERNEL-010");
    }

    @Test void unguardedDeviceStoreIsNotAccepted() {
        errorContains(CANONICAL.replace("if i >= n { return; }", ""),
                "GPU-KERNEL-010");
    }

    @Test void wrongGlobalLaneIsNotACapacityProof() {
        errorContains(CANONICAL.replace("gpu.global_id(0)", "gpu.global_id(1)"),
                "GPU-KERNEL-010");
    }

    @Test void optionalLegacyFiAfterBracedGpuGuardRemainsSourceCompatible() {
        assertFalse(lower(CANONICAL.replace("if i >= n { return; }",
                "if i >= n { return; } fi")).executable());
    }

    @Test void HostIfWithoutFiIsStillRejectedOutsideKernel() {
        var failure = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("pub fnc host() -> void { if true { return; } }"));
        assertTrue(failure.getMessage().contains("fi"), failure::getMessage);
    }

    @Test void duplicateGpuParametersGetStableGpuDiagnostic() {
        var source = CANONICAL.replace("u64 n", "u64 n, u64 n");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(failure.getMessage().contains("GPU-KERNEL-003"), failure::getMessage);
    }

    @Test void sourceIsStillRejectedAtProductionDispatchGate() {
        var failure = assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck(CANONICAL));
        assertTrue(failure.getMessage().contains("GPU-KERNEL-008"), failure::getMessage);
        assertTrue(failure.getMessage().contains("no implicit CPU fallback"), failure::getMessage);
    }
}
