package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuKernelSourceHirV1;
import dev.oreslang.compiler.GpuKernelSourceLinkV1;
import dev.oreslang.compiler.GpuKernelTypedSourceArgsV1;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deliberately strict direct-launch host source subset.
 * The parser is invoked OUTSIDE assertThrows, so syntax failures never
 * masquerade as GPU source-ownership or control-flow rejections.
 */
final class GpuKernelTypedSourceControlFlowV1Test {
    private static final String KERNEL = """
            pub gpu fnc saxpy(
                f32 alpha, GpuReadBuffer<f32> x, GpuReadBuffer<f32> y,
                GpuWriteBuffer<f32> out, int n
            ): void {
                const int i = gpu.global_id(0);
                if i < n { out[i] = alpha * x[i] + y[i]; } fi
            }
            """;

    private static final String LAUNCH = """
            val pending = launch saxpy(alpha, x, y, out, n) on gpu(device) {
                over: [n], group: [256], queue: queue
            };
            """;
    private static String callerSource(String body) {
        return """
                pub fnc submit(
                    f32 alpha, GpuReadBuffer<f32> x, GpuReadBuffer<f32> y,
                    GpuWriteBuffer<f32> out, int n
                ): void {
                """ + body + "\n}\n";
    }

    private record Fixture(GpuKernelSourceHirV1.Plan kernel,
                           GpuKernelSourceLinkV1.Link link) { }

    private static Fixture canonical() {
        Ast.Program parsed = Parser.parse(KERNEL + callerSource(LAUNCH + "return;\n"));
        var kernel = GpuKernelSourceHirV1.lower(
                (Ast.GpuKernelDecl) parsed.modules().getFirst().declarations().getFirst());
        var link = GpuKernelSourceLinkV1.audit(parsed);
        assertEquals(1, link.links().size());
        return new Fixture(kernel, link.links().getFirst());
    }

    private static Ast.FunctionDecl parsedCaller(String body) {
        Ast.Program parsed = Parser.parse(KERNEL + callerSource(body));
        return (Ast.FunctionDecl) parsed.modules().getFirst().declarations().get(1);
    }

    private static void rejects(String body, String diagnosticFragment) {
        Fixture reference = canonical();
        // This parse is intentionally NOT inside assertThrows.
        Ast.FunctionDecl modified = parsedCaller(body);
        var error = assertThrows(IllegalArgumentException.class,
                () -> GpuKernelTypedSourceArgsV1.audit(
                        modified, reference.kernel(), reference.link()));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-002"), error::getMessage);
        assertTrue(error.getMessage().contains(diagnosticFragment), error::getMessage);
    }

    @Test void canonicalDirectLaunchWithTrailingReturnKeepsNonExecutableEvidence() {
        Fixture reference = canonical();
        var report = GpuKernelTypedSourceArgsV1.audit(
                parsedCaller(LAUNCH + "return;\n"), reference.kernel(), reference.link());
        assertEquals(5, report.bindings().size());
        assertFalse(report.executable());
    }

    @Test void directLaunchWithoutReturnIsSourceShapeOnly() {
        Fixture reference = canonical();
        var report = GpuKernelTypedSourceArgsV1.audit(
                parsedCaller(LAUNCH), reference.kernel(), reference.link());
        assertFalse(report.executable());
    }

    @Test void additionalHostCallBeforeLaunchFailsClosed() {
        rejects("hostCall();\n" + LAUNCH + "return;\n", "unproven host statement");
    }

    @Test void additionalHostCallAfterLaunchFailsClosed() {
        rejects(LAUNCH + "hostCall();\nreturn;\n", "unproven host statement");
    }

    @Test void constLocalBeforeLaunchCannotMasqueradeAsHostFormal() {
        rejects("const int m = 5;\n" + LAUNCH + "return;\n",
                "unproven host statement");
    }

    @Test void localShadowOfCanonicalNIsDenied() {
        rejects("const int n = 3;\n" + LAUNCH + "return;\n",
                "unproven host statement");
    }

    @Test void localShadowOfReadViewIsDenied() {
        rejects("const int x = 5;\n" + LAUNCH + "return;\n",
                "unproven host statement");
    }

    @Test void earlyReturnBeforeLaunchCannotProduceBindingEvidence() {
        rejects("return;\n" + LAUNCH, "optional trailing bare return");
    }

    @Test void statementsAfterTrailingReturnAreNotIncludedInEvidence() {
        rejects(LAUNCH + "return;\nhostCall();\n", "unreachable statement");
    }

    @Test void conditionalLaunchNeedsCompleteControlFlowProof() {
        rejects("if true {\n" + LAUNCH + "} fi\nreturn;\n",
                "unproven host control flow");
    }

    @Test void twoSequentialLaunchesNeedDistinctOwnershipProofs() {
        rejects(LAUNCH + LAUNCH.replace("val pending", "val second") + "return;\n",
                "multiple source GPU launches");
    }

    @Test void letBoundLaunchResultDoesNotProveImmutableSourceBinding() {
        rejects(LAUNCH.replace("val pending", "let pending") + "return;\n",
                "mutable, inferred or shadowing");
    }

    @Test void launchResultCannotShadowAnInputBufferName() {
        rejects(LAUNCH.replace("val pending", "val x") + "return;\n",
                "mutable, inferred or shadowing");
    }

    @Test void returnWithValueHasNotBeenEffectChecked() {
        rejects(LAUNCH + "return alpha;\n", "optional trailing bare return");
    }

    @Test void unrelatedKernelNameDoesNotBindToCanonicalSourceLink() {
        rejects(LAUNCH.replace("launch saxpy(", "launch otherKernel(") + "return;\n",
                "target differs");
    }

    @Test void plainBindingWithoutGpuLaunchDoesNotMintEvidence() {
        rejects("const int x = 5;\nreturn;\n", "unproven host statement");
    }

    @Test void unrelatedLogicalOverSymbolCannotStandInForKernelN() {
        rejects(LAUNCH.replace("over: [n]", "over: [m]") + "return;\n",
                "over extent must use source parameter n");
    }

    @Test void arithmeticLogicalOverIsNotYetAnOwnershipOrCapacityProof() {
        rejects(LAUNCH.replace("over: [n]", "over: [n + 0]") + "return;\n",
                "over extent must use source parameter n");
    }

    @Test void literalOverCannotClaimRuntimeNCapacity() {
        rejects(LAUNCH.replace("over: [n]", "over: [256]") + "return;\n",
                "over extent must use source parameter n");
    }

    @Test void dynamicWorkgroupSizeRequiresTypedDeviceLimitsProof() {
        rejects(LAUNCH.replace("group: [256]", "group: [threads]") + "return;\n",
                "static positive group size");
    }

    @Test void nontrivialSecondaryLogicalExtentCannotUse1dTailProof() {
        rejects(LAUNCH.replace("over: [n]", "over: [n, 2]") + "return;\n",
                "one-dimensional padded launch geometry");
    }

    @Test void nontrivialSecondaryGroupAxisCannotUse1dTailProof() {
        rejects(LAUNCH.replace("group: [256]", "group: [256, 2]") + "return;\n",
                "one-dimensional padded launch geometry");
    }

    @Test void physicalGridAndBlockAreNotEquivalentToLogicalOverNProof() {
        rejects(LAUNCH.replace("over: [n], group: [256]",
                "grid: [1], block: [256]") + "return;\n",
                "logical over/group");
    }

    @Test void explicitUnitTrailingAxesPreserveCanonicalOneDimensionalSource() {
        var reference = canonical();
        String explicitAxes = LAUNCH
                .replace("over: [n]", "over: [n, 1, 1]")
                .replace("group: [256]", "group: [256, 1, 1]");
        var report = GpuKernelTypedSourceArgsV1.audit(
                parsedCaller(explicitAxes + "return;\n"),
                reference.kernel(), reference.link());
        assertFalse(report.executable());
        assertEquals(5, report.bindings().size());
    }
}
