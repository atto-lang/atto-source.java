package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuKernelSourceHirV1;
import dev.oreslang.compiler.GpuKernelTailGuardV1;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Source-only tail-guard checks; not GPU execution or allocation-capacity proof. */
final class GpuKernelTailGuardV1Test {
    private static final String SAXPY = """
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

    private static GpuKernelSourceHirV1.Plan kernel(String src) {
        Ast.Program program = Parser.parse(src);
        return GpuKernelSourceHirV1.lower((Ast.GpuKernelDecl)
                program.modules().getFirst().declarations().getFirst());
    }

    @Test void guardedSaxpyChecksTwoLoadsAndOneStoreWithoutMintingAuthority() {
        var plan = kernel(SAXPY);
        var report = GpuKernelTailGuardV1.audit(plan);
        assertEquals(3, report.inspectedMemoryOps());
        assertEquals(GpuKernelTailGuardV1.Evidence.SOURCE_TAIL_GUARD_ONLY_NOT_EXECUTABLE,
                report.evidence());
        assertFalse(report.executable());
        assertFalse(plan.executable());
    }

    @Test void unguardedStoreIsRejected() {
        var err = assertThrows(IllegalArgumentException.class, () ->
                kernel(SAXPY.replace("if i >= n { return; }", "")));
        assertTrue(err.getMessage().contains("GPU-KERNEL-010"), err::getMessage);
    }

    @Test void reversedGuardDoesNotEstablishLaneBound() {
        var err = assertThrows(IllegalArgumentException.class, () ->
                kernel(SAXPY.replace("if i >= n {", "if i <= n {")));
        assertTrue(err.getMessage().contains("GPU-KERNEL-010"), err::getMessage);
    }

    @Test void guardingLaneOneDoesNotProve1dLaneZeroSafety() {
        var err = assertThrows(IllegalArgumentException.class, () ->
                kernel(SAXPY.replace("gpu.global_id(0)", "gpu.global_id(1)")));
        assertTrue(err.getMessage().contains("GPU-KERNEL-010"), err::getMessage);
    }

    @Test void nonLaneIndexInsideGuardIsNotClaimedSafe() {
        var err = assertThrows(IllegalArgumentException.class, () ->
                kernel(SAXPY.replace("out[i] =", "out[0] =")));
        assertTrue(err.getMessage().contains("GPU-KERNEL-010"), err::getMessage);
    }

    @Test void guardedReadCannotEscapeTheBranch() {
        var err = assertThrows(IllegalArgumentException.class, () ->
                kernel(SAXPY.replace("if i >= n {", "if n >= i {")));
        assertTrue(err.getMessage().contains("GPU-KERNEL-010"), err::getMessage);
    }

    @Test void explicitEarlyReturnGuardCanProtectFallthrough() {
        var candidate = kernel(SAXPY);
        assertEquals(3, candidate.body().size());
        var binding = candidate.body().getFirst();
        var guard = candidate.body().get(1);
        var store = candidate.body().get(2);
        assertEquals("RETURN_VOID", guard.body().getFirst().opcode());
        var unguarded = new GpuKernelSourceHirV1.Plan(candidate.symbol(),
                candidate.parameters(), candidate.returnType(),
                List.of(binding, store), candidate.evidence());
        assertThrows(IllegalArgumentException.class,
                () -> GpuKernelTailGuardV1.audit(unguarded));
        var report = GpuKernelTailGuardV1.audit(candidate);
        assertEquals(3, report.inspectedMemoryOps());
        assertFalse(report.executable());
    }

    @Test void nonReturningInverseGuardCannotAuthorizeFallthrough() {
        var candidate = kernel(SAXPY);
        var guard = candidate.body().get(1);
        var noReturn = new GpuKernelSourceHirV1.Instruction(
                "IF", null, guard.value(), List.of());
        var forged = new GpuKernelSourceHirV1.Plan(candidate.symbol(),
                candidate.parameters(), candidate.returnType(),
                List.of(candidate.body().getFirst(), noReturn,
                        candidate.body().get(2)), candidate.evidence());
        assertThrows(IllegalArgumentException.class,
                () -> GpuKernelTailGuardV1.audit(forged));
    }

    @Test void aDifferentIntegerParameterCannotStandInForCapacityBoundN() {
        String changed = SAXPY.replace("u64 n", "u64 n, u64 m")
                .replace("if i >= n", "if i >= m");
        var error = assertThrows(IllegalArgumentException.class, () -> kernel(changed));
        assertTrue(error.getMessage().contains("GPU-KERNEL-010"), error::getMessage);
    }

    @Test void aConstantGuardCannotClaimSourceCapacityProof() {
        String changed = SAXPY.replace("if i >= n", "if i >= 1024");
        var error = assertThrows(IllegalArgumentException.class, () -> kernel(changed));
        assertTrue(error.getMessage().contains("GPU-KERNEL-002"), error::getMessage);
    }

}
