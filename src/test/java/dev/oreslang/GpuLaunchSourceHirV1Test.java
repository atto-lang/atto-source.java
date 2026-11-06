package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuLaunchSourceHirV1;
import dev.oreslang.compiler.GpuLaunchSourceHirV1.Constant;
import dev.oreslang.compiler.GpuLaunchSourceHirV1.Mode;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GpuLaunchSourceHirV1Test {
    private static Ast.Expr name(String n) { return new Ast.NameExpr(n); }
    private static Ast.Expr number(long n) { return new Ast.LiteralExpr(n); }
    private static Ast.GpuLaunchExpr physical(Ast.Expr x) {
        return new Ast.GpuLaunchExpr(name("saxpy"),
                List.of(name("alpha"), name("x"), name("y"), name("out"), name("n")),
                name("device"), new Ast.GpuGridGeometry(
                        List.of(x, number(1), number(1)),
                        List.of(number(256), number(1), number(1))),
                name("queue"), number(0));
    }
    private static String source(String extent) {
        return "pub fnc main(): void {"
                + " val pending = launch saxpy(a, x, y, out, n) on gpu(device) {"
                + " grid: [" + extent + ", 1, 1], block: [256, 1, 1], queue: queue };"
                + " return; }";
    }

    @Test void sourceCandidateCannotBeMistakenForExecutableGpuProof() {
        var c = GpuLaunchSourceHirV1.lower(physical(number(4)));
        assertEquals("saxpy", c.kernelSymbol());
        assertEquals("device", c.deviceSymbol());
        assertEquals("queue", c.queueSymbol());
        assertEquals(Mode.PHYSICAL_GRID_BLOCK, c.geometry().mode());
        assertEquals(new Constant(4), c.geometry().extent().getFirst());
        assertEquals(5, c.orderedArguments().size());
        assertFalse(c.executable());
        assertEquals(GpuLaunchSourceHirV1.Evidence.SOURCE_SHAPE_ONLY_NOT_EXECUTABLE,
                c.evidence());
    }

    @Test void candidateDoesNotRetainMutableArgumentArray() {
        ArrayList<Ast.Expr> sourceArgs = new ArrayList<>(List.of(name("alpha")));
        var c = GpuLaunchSourceHirV1.lower(new Ast.GpuLaunchExpr(
                name("kernel"), sourceArgs, name("device"),
                new Ast.GpuOverGeometry(List.of(number(0), number(1), number(1)),
                        List.of(number(16), number(1), number(1))),
                name("queue"), number(0)));
        sourceArgs.add(name("late"));
        assertEquals(1, c.orderedArguments().size());
        assertEquals(Mode.LOGICAL_OVER_GROUP, c.geometry().mode());
        assertEquals(new Constant(0), c.geometry().extent().getFirst());
    }

    @Test void physicalZeroGridRejectedLogicalZeroAllowedAsSourceOnly() {
        var e = assertThrows(IllegalArgumentException.class, () ->
                GpuLaunchSourceHirV1.lower(physical(number(0))));
        assertTrue(e.getMessage().contains("GPU-LAUNCH-003"));
        var logical = GpuLaunchSourceHirV1.lower(new Ast.GpuLaunchExpr(
                name("kernel"), List.of(), name("device"),
                new Ast.GpuOverGeometry(List.of(number(0), number(1), number(1)),
                        List.of(number(16), number(1), number(1))),
                name("queue"), number(0)));
        assertFalse(logical.executable());
    }

    @Test void checkedCeilDivAndOverflowAreNotEvaluatedAsHostCalls() {
        var ceil = new Ast.CallExpr(name("ceil_div"), List.of(name("n"), number(256)));
        var c = GpuLaunchSourceHirV1.lower(physical(ceil));
        assertInstanceOf(GpuLaunchSourceHirV1.CeilDiv.class,
                c.geometry().extent().getFirst());
        var overflow = new Ast.BinaryExpr("+", number(Long.MAX_VALUE), number(1));
        var e = assertThrows(IllegalArgumentException.class, () ->
                GpuLaunchSourceHirV1.lower(physical(overflow)));
        assertTrue(e.getMessage().contains("GPU-LAUNCH-003"));
    }

    @Test void arbitraryCallsAndInvalidDeviceExpressionsAreNotShapeProofs() {
        var call = new Ast.CallExpr(name("hostCall"), List.of());
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                GpuLaunchSourceHirV1.lower(physical(call)))
                .getMessage().contains("GPU-LAUNCH-003"));
        var wrongDevice = new Ast.GpuLaunchExpr(name("saxpy"), List.of(),
                call, new Ast.GpuGridGeometry(
                        List.of(number(1), number(1), number(1)),
                        List.of(number(1), number(1), number(1))),
                name("queue"), number(0));
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                GpuLaunchSourceHirV1.lower(wrongDevice))
                .getMessage().contains("GPU-LAUNCH-001"));
    }

    @Test void effectfulGeometryRejectedBeforeGenericMissingProviderDiagnostic() {
        var effectful = physical(new Ast.AwaitExpr(name("pending")));
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                GpuLaunchSourceHirV1.lower(effectful))
                .getMessage().contains("GPU-LAUNCH-006"));
    }

    @Test void productionTypecheckerStillDeniesExecutionAfterSourceHirAnalysis() {
        var e = assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck(source("ceil_div(n, 256)")));
        assertTrue(e.getMessage().contains("GPU-LAUNCH-008"), e::getMessage);
        assertTrue(e.getMessage().contains("no implicit CPU fallback"));
    }

    @Test void productionTypecheckerRejectsBoundBooleanGridDimension() {
        var input = "pub fnc main(): void {"
                + " bool flag = true;"
                + " val pending = launch saxpy(a, x, y, out, n) on gpu(device) {"
                + " grid: [flag, 1, 1], block: [256, 1, 1], queue: queue };"
                + " return; }";
        var error = assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck(input));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-003"), error::getMessage);
    }

    @Test void productionTypecheckerRejectsUnsupportedGeometryHostCallEarly() {
        var e = assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck(source("hostCall()")));
        assertTrue(e.getMessage().contains("GPU-LAUNCH-003"), e::getMessage);
    }
}
