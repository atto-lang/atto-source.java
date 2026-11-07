package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuKernelSourceCapacityJoinV1;
import dev.oreslang.compiler.GpuKernelSourceHirV1;
import dev.oreslang.compiler.GpuKernelSourceLinkV1;
import dev.oreslang.compiler.GpuKernelTypedSourceArgsV1;
import dev.oreslang.compiler.GpuLaunchFootprints.Access;
import dev.oreslang.compiler.GpuLaunchFootprints.Limits;
import dev.oreslang.compiler.GpuLaunchFootprints.QueueScope;
import dev.oreslang.compiler.GpuLaunchFootprints.View;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Source declaration ABI shape only. Never authority for a GPU provider loan. */
final class GpuKernelTypedSourceArgsV1Test {
    private static final String KERNEL = """
            pub gpu fnc saxpy(
                f32 alpha, GpuReadBuffer<f32> x, GpuReadBuffer<f32> y,
                GpuWriteBuffer<f32> out, int n
            ): void {
                const int i = gpu.global_id(0);
                if i < n { out[i] = alpha * x[i] + y[i]; } fi
            }
            """;

    private static final String HOST = """
            pub fnc submit(
                f32 alpha, GpuReadBuffer<f32> x, GpuReadBuffer<f32> y,
                GpuWriteBuffer<f32> out, int n
            ): void {
                val pending = launch saxpy(alpha, x, y, out, n) on gpu(device) {
                    over: [n], group: [256], queue: queue
                };
                return;
            }
            """;

    private record Candidate(Ast.FunctionDecl caller,
                             GpuKernelSourceHirV1.Plan kernel,
                             GpuKernelSourceLinkV1.Link link) { }

    private static Candidate candidate(String kernelText, String hostText) {
        Ast.Program program = Parser.parse(kernelText + hostText);
        var declarations = program.modules().getFirst().declarations();
        var kernel = GpuKernelSourceHirV1.lower((Ast.GpuKernelDecl) declarations.getFirst());
        var caller = (Ast.FunctionDecl) declarations.get(1);
        var links = GpuKernelSourceLinkV1.audit(program);
        assertEquals(1, links.links().size(), "one lexical launch must be bound");
        return new Candidate(caller, kernel, links.links().getFirst());
    }

    private static Candidate candidate(String hostText) {
        return candidate(KERNEL, hostText);
    }

    private static GpuKernelTypedSourceArgsV1.Report typed(Candidate c) {
        return GpuKernelTypedSourceArgsV1.audit(c.caller(), c.kernel(), c.link());
    }

    private static Map<Integer, View> views() {
        return Map.of(
                1, new View("tenant", "gpu", "x", 9, 1024, 0, 1024, 4, Access.READ),
                2, new View("tenant", "gpu", "y", 9, 1024, 0, 1024, 4, Access.READ),
                3, new View("tenant", "gpu", "out", 9, 1024, 0, 1024, 4, Access.WRITE));
    }

    @Test void explicitlyDeclaredHostBufferTypesMatchKernelWithoutGpuAuthority() {
        var typed = typed(candidate(HOST));
        assertEquals("submit", typed.caller());
        assertEquals("saxpy", typed.kernel());
        assertEquals(5, typed.bindings().size());
        assertEquals(GpuKernelTypedSourceArgsV1.Evidence
                .DECLARED_HOST_ABI_SHAPE_ONLY_NOT_EXECUTABLE, typed.evidence());
        assertTrue(typed.bindings().get(1).deviceBuffer());
        assertFalse(typed.bindings().get(0).deviceBuffer());
        assertFalse(typed.executable());
    }

    @Test void typedSourceAndCapacityAreCombinedButRemainNonExecutable() {
        var c = candidate(HOST);
        var report = GpuKernelSourceCapacityJoinV1.auditTypedSource(
                c.caller(), c.kernel(), c.link(),
                new QueueScope("tenant", "gpu", "stream", 3),
                256, 256, views(), new Limits(16, 65536));
        assertEquals(3, report.capacity().checkedViews());
        assertEquals(5, report.sourceTypes().bindings().size());
        assertFalse(report.executable());
        assertFalse(report.capacity().executable());
        assertFalse(report.sourceTypes().executable());
    }

    @Test void wrongHostReadBufferCapabilityMustFailSourceBinding() {
        String bad = HOST.replace("GpuReadBuffer<f32> x", "GpuWriteBuffer<f32> x");
        var error = assertThrows(IllegalArgumentException.class,
                () -> typed(candidate(bad)));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-002"), error::getMessage);
    }

    @Test void wrongHostWriteBufferCapabilityMustFailSourceBinding() {
        String bad = HOST.replace("GpuWriteBuffer<f32> out", "GpuReadBuffer<f32> out");
        assertThrows(IllegalArgumentException.class, () -> typed(candidate(bad)));
    }

    @Test void differentBufferElementTypeCannotImplicitlyConvert() {
        String bad = HOST.replace("GpuReadBuffer<f32> x", "GpuReadBuffer<i64> x");
        assertThrows(IllegalArgumentException.class, () -> typed(candidate(bad)));
    }

    @Test void f64ScalarIsNotSilentlyConvertedToKernelF32() {
        String bad = HOST.replace("f32 alpha", "f64 alpha");
        assertThrows(IllegalArgumentException.class, () -> typed(candidate(bad)));
    }

    @Test void unresolvedHostNameFailsEvenIfLaunchLinkHasMatchingArity() {
        String bad = HOST.replace("saxpy(alpha, x, y, out, n)",
                                  "saxpy(alpha, unknown, y, out, n)");
        assertThrows(IllegalArgumentException.class, () -> typed(candidate(bad)));
    }

    @Test void declaredNMustMatchKernelIntegerType() {
        String bad = HOST.replace("int n", "bool n");
        assertThrows(IllegalArgumentException.class, () -> typed(candidate(bad)));
    }

    @Test void literalNUsesAnExactI64SourceArgument() {
        String source = HOST.replace("saxpy(alpha, x, y, out, n)",
                                     "saxpy(alpha, x, y, out, 256)");
        var typed = typed(candidate(source));
        assertEquals(GpuKernelSourceHirV1.Type.I64,
                typed.bindings().get(4).actualType());
        assertFalse(typed.executable());
    }

    @Test void readOnlyAliasIsNotAWriteLoanOrAllocationProof() {
        String source = HOST.replace("saxpy(alpha, x, y, out, n)",
                                     "saxpy(alpha, x, x, out, n)");
        var typed = typed(candidate(source));
        assertEquals(5, typed.bindings().size());
        assertFalse(typed.executable());
    }

    @Test void repeatedWritableSourceArgumentFailsBeforeProvider() {
        String kernel = """
                pub gpu fnc two(GpuWriteBuffer<f32> a, GpuWriteBuffer<f32> b, int n): void {
                    return;
                }
                """;
        String host = """
                pub fnc submit(GpuWriteBuffer<f32> out, int n): void {
                    val pending = launch two(out, out, n) on gpu(device) {
                        over: [n], group: [64], queue: queue
                    };
                    return;
                }
                """;
        var error = assertThrows(IllegalArgumentException.class,
                () -> typed(candidate(kernel, host)));
        assertTrue(error.getMessage().contains("writable device buffer alias"),
                error::getMessage);
    }

    @Test void literalSourceNStillDoesNotProveActualDeviceCapacity() {
        String source = HOST.replace("saxpy(alpha, x, y, out, n)",
                                     "saxpy(alpha, x, y, out, 255)");
        var c = candidate(source);
        assertFalse(typed(c).executable());
        assertThrows(IllegalArgumentException.class,
                () -> GpuKernelSourceCapacityJoinV1.auditTypedSource(
                        c.caller(), c.kernel(), c.link(),
                        new QueueScope("tenant", "gpu", "stream", 3),
                        256, 256, views(), new Limits(16, 65536)));
    }

    @Test void unknownGenericOrNestedBufferTypeFailsClosed() {
        String bad = HOST.replace("GpuReadBuffer<f32> x",
                "GpuReadBuffer<GpuReadBuffer<f32>> x");
        assertThrows(IllegalArgumentException.class, () -> typed(candidate(bad)));
    }
}
