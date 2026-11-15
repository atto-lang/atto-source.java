package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuKernelSourceLinkV1;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Source-only kernel/launch signature links; no physical device evidence. */
final class GpuKernelSourceLinkV1Test {
    private static final String KERNEL = """
            pub gpu fnc saxpy(
                f32 alpha, GpuArray<f32> x, GpuArray<f32> y,
                GpuArray<f32> mut out, u64 n
            ) -> void {
                const u64 i = gpu.global_id(0);
                if i >= n { return; }
                out[i] = alpha * x[i] + y[i];
            }
            """;

    private static final String HOST = """
            pub fnc main(): void {
                val pending = launch saxpy(alpha, x, y, out, n) on gpu(device) {
                    over: [n], group: [256], queue: queue
                };
                return;
            }
            """;

    private static GpuKernelSourceLinkV1.Report audit(String source) {
        return GpuKernelSourceLinkV1.audit(Parser.parse(source));
    }

    @Test void sourceLinkMatchesCanonicalKernelAndFiveArguments() {
        var report = audit(KERNEL + HOST);
        assertFalse(report.executable());
        assertEquals(GpuKernelSourceLinkV1.Evidence.SOURCE_SIGNATURE_ONLY_NOT_EXECUTABLE,
                report.evidence());
        assertEquals(1, report.links().size());
        var link = report.links().getFirst();
        assertEquals("saxpy", link.kernel());
        assertEquals(5, link.arguments().size());
        assertEquals(GpuKernelSourceLinkV1.ArgumentKind.LEXICAL_NAME_UNTYPED,
                link.arguments().getFirst().kind());
        assertFalse(link.executable());
    }

    @Test void productionCompilerStillRejectsEvenMatchedKernel() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(KERNEL + HOST));
        assertTrue(error.getMessage().contains("GPU-KERNEL-008"), error::getMessage);
        assertTrue(error.getMessage().contains("no implicit CPU fallback"), error::getMessage);
    }

    @Test void missingArgumentFailsClosedBeforeDispatch() {
        var error = assertThrows(IllegalArgumentException.class, () ->
                audit(KERNEL + HOST.replace("saxpy(alpha, x, y, out, n)",
                        "saxpy(alpha, x, y, out)")));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-002"), error::getMessage);
    }

    @Test void unknownKernelDoesNotResolveHostCallable() {
        var error = assertThrows(IllegalArgumentException.class, () ->
                audit(KERNEL + HOST.replace("launch saxpy(", "launch unknown(")));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-001"), error::getMessage);
    }

    @Test void hostCallArgumentNotClaimedAsDeviceSafe() {
        var error = assertThrows(IllegalArgumentException.class, () ->
                audit(KERNEL + HOST.replace("saxpy(alpha, x, y, out, n)",
                        "saxpy(alpha, x, y, out, hostCall())")));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-006"), error::getMessage);
    }

    @Test void noImplicitIntegerToFloatKernelArgumentConversion() {
        var error = assertThrows(IllegalArgumentException.class, () ->
                audit(KERNEL + HOST.replace("saxpy(alpha, x, y, out, n)",
                        "saxpy(5, x, y, out, n)")));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-002"), error::getMessage);
    }

    @Test void duplicateGpuKernelsAreRejected() {
        var error = assertThrows(IllegalArgumentException.class, () ->
                audit(KERNEL + KERNEL));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-001"), error::getMessage);
    }

    @Test void kernelAndHostDeclarationCannotUseSameModuleName() {
        var source = KERNEL
                + "pub fnc saxpy(): void { return; }\n";
        var error = assertThrows(IllegalArgumentException.class, () -> audit(source));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-001"), error::getMessage);
    }

    @Test void qualifiedCrossModuleKernelStillNeedsVerifiedImport() {
        Ast.Program parsed = Parser.parse(KERNEL
                + HOST.replace("launch saxpy(", "launch gpu_math.saxpy("));
        Ast.GpuKernelDecl kernel = (Ast.GpuKernelDecl)
                parsed.modules().getFirst().declarations().getFirst();
        Ast.FunctionDecl host = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().get(1);
        Ast.ModuleDecl kernelModule = new Ast.ModuleDecl("gpu_math", List.of(kernel));
        Ast.ModuleDecl hostModule = new Ast.ModuleDecl("host", List.of(host));
        var error = assertThrows(IllegalArgumentException.class,
                () -> GpuKernelSourceLinkV1.audit(
                        new Ast.Program(List.of(kernelModule, hostModule))));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-001"), error::getMessage);
        assertTrue(error.getMessage().contains("verified typed import"), error::getMessage);
    }

    @Test void unrelatedModuleCannotAcquireAnUnqualifiedGpuKernel() {
        Ast.Program parsed = Parser.parse(KERNEL + HOST);
        Ast.GpuKernelDecl kernel = (Ast.GpuKernelDecl)
                parsed.modules().getFirst().declarations().getFirst();
        Ast.FunctionDecl host = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().get(1);
        var assembled = new Ast.Program(List.of(
                new Ast.ModuleDecl("gpu_math", List.of(kernel)),
                new Ast.ModuleDecl("host", List.of(host))));
        var error = assertThrows(IllegalArgumentException.class,
                () -> GpuKernelSourceLinkV1.audit(assembled));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-001"), error::getMessage);
    }

    @Test void privateGpuKernelIsNotCallableFromAnotherModuleEvenWhenQualified() {
        String privateKernel = KERNEL.replace("pub gpu fnc", "private gpu fnc");
        Ast.Program parsed = Parser.parse(privateKernel
                + HOST.replace("launch saxpy(", "launch gpu_math.saxpy("));
        Ast.GpuKernelDecl kernel = (Ast.GpuKernelDecl)
                parsed.modules().getFirst().declarations().getFirst();
        Ast.FunctionDecl host = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().get(1);
        var assembled = new Ast.Program(List.of(
                new Ast.ModuleDecl("gpu_math", List.of(kernel)),
                new Ast.ModuleDecl("host", List.of(host))));
        var error = assertThrows(IllegalArgumentException.class,
                () -> GpuKernelSourceLinkV1.audit(assembled));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-001"), error::getMessage);
        assertTrue(error.getMessage().contains("private GPU kernel"), error::getMessage);
    }

    @Test void localPrivateKernelDoesNotBecomeCrossModuleCapability() {
        String privateKernel = KERNEL.replace("pub gpu fnc", "private gpu fnc");
        var report = audit(privateKernel + HOST);
        assertEquals(1, report.links().size());
        assertFalse(report.executable());
    }
}
