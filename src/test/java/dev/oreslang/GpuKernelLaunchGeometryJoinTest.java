package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuKernelSourceCapacityJoinV1;
import dev.oreslang.compiler.GpuKernelSourceHirV1;
import dev.oreslang.compiler.GpuKernelSourceLinkV1;
import dev.oreslang.compiler.GpuLaunchFootprints.Access;
import dev.oreslang.compiler.GpuLaunchFootprints.Limits;
import dev.oreslang.compiler.GpuLaunchFootprints.QueueScope;
import dev.oreslang.compiler.GpuLaunchFootprints.View;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The canonical 1D kernel indexes only gpu.global_id(0). The join must bind
 * the launch's own source geometry, not just a caller-supplied extent:
 * extra logical/physical axes run several lanes per index (racing writes to
 * the same out[i]) and an over extent unrelated to n breaks the capacity proof.
 */
final class GpuKernelLaunchGeometryJoinTest {
    private static final String KERNEL = """
            pub gpu fnc saxpy(
                f32 alpha, GpuReadBuffer<f32> x, GpuReadBuffer<f32> y,
                GpuWriteBuffer<f32> out, int n
            ): void {
                const int i = gpu.global_id(0);
                if i < n { out[i] = alpha * x[i] + y[i]; } fi
            }
            """;
    private static final QueueScope QUEUE = new QueueScope("tenant", "gpu", "stream", 9);
    private static final Limits BUDGET = new Limits(16, 65536);

    private static String host(String geometry, String nArgument) {
        return "pub fnc main(): void {\n"
                + "    val pending = launch saxpy(alpha, x, y, out, " + nArgument + ") on gpu(device) {\n"
                + "        " + geometry + ", queue: queue\n"
                + "    };\n"
                + "    return;\n"
                + "}\n";
    }

    private static GpuKernelSourceCapacityJoinV1.Report audit(String host, long n) {
        Ast.Program parsed = Parser.parse(KERNEL + host);
        var kernel = (Ast.GpuKernelDecl) parsed.modules().getFirst().declarations().getFirst();
        var plan = GpuKernelSourceHirV1.lower(kernel);
        var link = GpuKernelSourceLinkV1.audit(parsed).links().getFirst();
        Map<Integer, View> views = Map.of(
                1, new View("tenant", "gpu", "x", 5, 1024, 0, 1024, 4, Access.READ),
                2, new View("tenant", "gpu", "y", 5, 1024, 0, 1024, 4, Access.READ),
                3, new View("tenant", "gpu", "out", 5, 1024, 0, 1024, 4, Access.WRITE));
        return GpuKernelSourceCapacityJoinV1.audit(plan, link, QUEUE, n, n, views, BUDGET);
    }

    private static void rejected(String geometry, String nArgument, long n) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> audit(host(geometry, nArgument), n), geometry);
        assertTrue(error.getMessage().contains("GPU-LAUNCH-005"), error::getMessage);
    }

    @Test
    void canonicalOneDimensionalOverIsAdmittedAsNonExecutableEvidence() {
        assertFalse(audit(host("over: [n], group: [256]", "n"), 256).executable());
        assertFalse(audit(host("over: [n, 1, 1], group: [256, 1, 1]", "n"), 256).executable());
        assertFalse(audit(host("over: [256], group: [64]", "256"), 256).executable());
    }

    @Test
    void extraLogicalAxesWouldRaceSeveralLanesOnOneOutputElement() {
        rejected("over: [n, 4], group: [256]", "n", 256);
        rejected("over: [n, 1, 2], group: [256]", "n", 256);
        rejected("over: [n], group: [128, 2]", "n", 256);
    }

    @Test
    void overExtentMustBeTheCanonicalKernelN() {
        rejected("over: [m], group: [256]", "n", 256);
        rejected("over: [n + 1], group: [256]", "n", 256);
        rejected("over: [512], group: [256]", "256", 256);
    }

    @Test
    void physicalGridBlockFormHasNoBoundLogicalExtent() {
        rejected("grid: [1, 1, 1], block: [256, 1, 1]", "n", 256);
        rejected("grid: [1, 2, 1], block: [256, 1, 1]", "n", 256);
    }
}
