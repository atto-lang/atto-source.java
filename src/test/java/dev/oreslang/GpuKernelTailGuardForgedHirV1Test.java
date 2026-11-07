package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuKernelSourceHirV1;
import dev.oreslang.compiler.GpuKernelTailGuardV1;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Attacker-constructible typed source records are NOT compiler-issued IR.
 * These mutations deliberately bypass the parser/lowerer and must fail
 * the source-tail audit before any host/device authority is considered.
 */
final class GpuKernelTailGuardForgedHirV1Test {
    private static final String SAXPY = """
            pub gpu fnc saxpy(
                f32 alpha, GpuReadBuffer<f32> x, GpuReadBuffer<f32> y,
                GpuWriteBuffer<f32> out, int n
            ): void {
                const int i = gpu.global_id(0);
                if i < n { out[i] = alpha * x[i] + y[i]; } fi
            }
            """;

    private static GpuKernelSourceHirV1.Plan canonical() {
        var program = Parser.parse(SAXPY);
        var kernel = (Ast.GpuKernelDecl)
                program.modules().getFirst().declarations().getFirst();
        return GpuKernelSourceHirV1.lower(kernel);
    }

    private static GpuKernelSourceHirV1.Value value(
            GpuKernelSourceHirV1.Type type, String opcode, String symbol,
            GpuKernelSourceHirV1.Value... operands) {
        return new GpuKernelSourceHirV1.Value(
                type, opcode, symbol, List.of(operands));
    }

    private static GpuKernelSourceHirV1.Plan withStore(
            GpuKernelSourceHirV1.Plan source, String target,
            GpuKernelSourceHirV1.Value rhs) {
        var guard = source.body().get(1);
        var store = guard.body().getFirst();
        var idx = store.value().operands().getFirst();
        var forged = new GpuKernelSourceHirV1.Instruction(
                "STORE_F32", target,
                value(GpuKernelSourceHirV1.Type.F32, "store.arguments", null, idx, rhs),
                List.of());
        var changedGuard = new GpuKernelSourceHirV1.Instruction(
                "IF", null, guard.value(), List.of(forged));
        return new GpuKernelSourceHirV1.Plan(
                source.symbol(), source.parameters(), source.returnType(),
                List.of(source.body().getFirst(), changedGuard), source.evidence());
    }

    private static void denied(GpuKernelSourceHirV1.Plan forged, String why) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> GpuKernelTailGuardV1.audit(forged));
        assertTrue(error.getMessage().contains("GPU-KERNEL-010"), error::getMessage);
        assertTrue(error.getMessage().contains(why), error::getMessage);
    }

    @Test void canonicalLoweredSaxpyStillHasThreeMemoryOpsAndNoExecutionPermit() {
        var report = GpuKernelTailGuardV1.audit(canonical());
        assertEquals(3, report.inspectedMemoryOps());
        assertFalse(report.executable());
    }

    @Test void forgedStoreIntoReadOnlyInputIsDenied() {
        var source = canonical();
        var rhs = source.body().get(1).body().getFirst().value().operands().get(1);
        denied(withStore(source, "x", rhs), "device write");
    }

    @Test void forgedStoreIntoScalarParameterIsDenied() {
        var source = canonical();
        var rhs = source.body().get(1).body().getFirst().value().operands().get(1);
        denied(withStore(source, "alpha", rhs), "device write");
    }

    @Test void forgedStoreIntoUndeclaredBufferIsDenied() {
        var source = canonical();
        var rhs = source.body().get(1).body().getFirst().value().operands().get(1);
        denied(withStore(source, "fabricated_out", rhs), "device write");
    }

    @Test void forgedReadFromScalarCannotMimicDeviceBuffer() {
        var source = canonical();
        var idx = source.body().get(1).body().getFirst().value().operands().getFirst();
        var scalar = value(GpuKernelSourceHirV1.Type.F32, "local", "alpha");
        var load = value(GpuKernelSourceHirV1.Type.F32, "buffer.load", null, scalar, idx);
        denied(withStore(source, "out", load), "buffer read");
    }

    @Test void forgedReadFromWriteOnlyBufferIsDenied() {
        var source = canonical();
        var idx = source.body().get(1).body().getFirst().value().operands().getFirst();
        var out = value(GpuKernelSourceHirV1.Type.BUFFER_WRITE_F32, "local", "out");
        var load = value(GpuKernelSourceHirV1.Type.F32, "buffer.load", null, out, idx);
        denied(withStore(source, "out", load), "buffer read");
    }

    @Test void forgedReadFromUndeclaredDeviceBufferIsDenied() {
        var source = canonical();
        var idx = source.body().get(1).body().getFirst().value().operands().getFirst();
        var phantom = value(GpuKernelSourceHirV1.Type.BUFFER_READ_F32, "local", "phantom");
        var load = value(GpuKernelSourceHirV1.Type.F32, "buffer.load", null, phantom, idx);
        denied(withStore(source, "out", load), "buffer read");
    }

    @Test void forgedHostCallOpcodeInsideGuardedStoreIsDenied() {
        var source = canonical();
        denied(withStore(source, "out",
                value(GpuKernelSourceHirV1.Type.F32, "host.call", "exfiltrate")),
                "unknown typed GPU source opcode");
    }

    @Test void forgedIntegerLiteralWithIncorrectTypeIsDenied() {
        var source = canonical();
        denied(withStore(source, "out",
                value(GpuKernelSourceHirV1.Type.F32, "i64.literal", "17")),
                "integer literal has wrong typed");
    }

    @Test void forgedArithmeticOperandsWithConflictingTypesAreDenied() {
        var source = canonical();
        var left = value(GpuKernelSourceHirV1.Type.F32, "local", "alpha");
        var right = value(GpuKernelSourceHirV1.Type.I64, "local", "n");
        denied(withStore(source, "out",
                value(GpuKernelSourceHirV1.Type.F32, "arith.+", null, left, right)),
                "arithmetic typed");
    }

    @Test void malformedTypedGpuGlobalIdAxisIsDenied() {
        var source = canonical();
        var wrongAxis = value(GpuKernelSourceHirV1.Type.I64, "gpu.global_id", "9");
        var alpha = value(GpuKernelSourceHirV1.Type.F32, "local", "alpha");
        var illicit = value(GpuKernelSourceHirV1.Type.F32, "arith.+", null, alpha,
                value(GpuKernelSourceHirV1.Type.F32, "buffer.load", null,
                        value(GpuKernelSourceHirV1.Type.BUFFER_READ_F32, "local", "x"),
                        wrongAxis));
        denied(withStore(source, "out", illicit), "buffer read");
    }

    @Test void forgedLocalWithUnboundNameIsDenied() {
        var source = canonical();
        denied(withStore(source, "out",
                value(GpuKernelSourceHirV1.Type.F32, "local", "unknown")),
                "unbound or mis-typed");
    }

    @Test void forgedLocalWithContradictoryTypeIsDenied() {
        var source = canonical();
        denied(withStore(source, "out",
                value(GpuKernelSourceHirV1.Type.F32, "local", "n")),
                "unbound or mis-typed");
    }

    @Test void nonKernelOpcodeCannotBeUsedAsTypedPredicate() {
        var source = canonical();
        var altered = new GpuKernelSourceHirV1.Instruction(
                "IF", null,
                value(GpuKernelSourceHirV1.Type.BOOL, "host.call", "host"),
                source.body().get(1).body());
        var forged = new GpuKernelSourceHirV1.Plan(
                source.symbol(), source.parameters(), source.returnType(),
                List.of(source.body().getFirst(), altered), source.evidence());
        denied(forged, "unknown typed GPU source opcode");
    }
}
