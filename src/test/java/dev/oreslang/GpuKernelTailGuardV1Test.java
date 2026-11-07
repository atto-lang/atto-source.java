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
                GpuReadBuffer<f32> x,
                GpuReadBuffer<f32> y,
                GpuWriteBuffer<f32> out,
                int n
            ): void {
                const int i = gpu.global_id(0);
                if i < n {
                    out[i] = alpha * x[i] + y[i];
                } fi
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
                kernel(SAXPY.replace("if i < n {", "if true {")));
        assertTrue(err.getMessage().contains("GPU-KERNEL-010"), err::getMessage);
    }

    @Test void reversedGuardDoesNotEstablishLaneBound() {
        var err = assertThrows(IllegalArgumentException.class, () ->
                kernel(SAXPY.replace("if i < n {", "if i > n {")));
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
                kernel(SAXPY.replace("if i < n {", "if n < i {")));
        assertTrue(err.getMessage().contains("GPU-KERNEL-010"), err::getMessage);
    }

    @Test void explicitEarlyReturnGuardCanProtectFallthrough() {
        var candidate = kernel(SAXPY);
        var statements = candidate.body();
        var binding = statements.getFirst();
        var branch = statements.get(1);
        var condition = branch.value();
        var negatedCondition = new GpuKernelSourceHirV1.Value(
                GpuKernelSourceHirV1.Type.BOOL, "compare.>=", null, condition.operands());
        var earlyReturn = new GpuKernelSourceHirV1.Instruction("IF", null,
                negatedCondition,
                List.of(new GpuKernelSourceHirV1.Instruction(
                        "RETURN_VOID", null, null, List.of())));
        var fallthrough = branch.body().getFirst();
        var unguardedProgram = new GpuKernelSourceHirV1.Plan(candidate.symbol(),
                candidate.parameters(), candidate.returnType(),
                List.of(binding, fallthrough), candidate.evidence());
        assertThrows(IllegalArgumentException.class,
                () -> GpuKernelTailGuardV1.audit(unguardedProgram));
        var guardedProgram = new GpuKernelSourceHirV1.Plan(candidate.symbol(),
                candidate.parameters(), candidate.returnType(),
                List.of(binding, earlyReturn, fallthrough), candidate.evidence());
        var report = GpuKernelTailGuardV1.audit(guardedProgram);
        assertEquals(3, report.inspectedMemoryOps());
        assertFalse(report.executable());
    }

    @Test void nonReturningInverseGuardCannotAuthorizeFallthrough() {
        var candidate = kernel(SAXPY);
        var branch = candidate.body().get(1);
        var inverse = new GpuKernelSourceHirV1.Value(
                GpuKernelSourceHirV1.Type.BOOL, "compare.>=", null,
                branch.value().operands());
        var ifNoReturn = new GpuKernelSourceHirV1.Instruction(
                "IF", null, inverse, List.of());
        var forged = new GpuKernelSourceHirV1.Plan(candidate.symbol(),
                candidate.parameters(), candidate.returnType(),
                List.of(candidate.body().getFirst(), ifNoReturn,
                        branch.body().getFirst()), candidate.evidence());
        assertThrows(IllegalArgumentException.class,
                () -> GpuKernelTailGuardV1.audit(forged));
    }

    @Test void aDifferentIntegerParameterCannotStandInForCapacityBoundN() {
        String changed = SAXPY.replace("int n", "int n, int m")
                .replace("if i < n", "if i < m");
        var error = assertThrows(IllegalArgumentException.class, () -> kernel(changed));
        assertTrue(error.getMessage().contains("GPU-KERNEL-010"), error::getMessage);
    }

    @Test void aConstantGuardCannotClaimSourceCapacityProof() {
        String changed = SAXPY.replace("if i < n", "if i < 1024");
        var error = assertThrows(IllegalArgumentException.class, () -> kernel(changed));
        assertTrue(error.getMessage().contains("GPU-KERNEL-010"), error::getMessage);
    }

    @Test void inversionMustUseTheSameCanonicalNBound() {
        String changed = SAXPY.replace("int n", "int n, int m")
                .replace("if i < n", "if i >= m { return; } if i < n");
        var plan = kernel(changed);
        assertFalse(plan.executable());
    }
}
