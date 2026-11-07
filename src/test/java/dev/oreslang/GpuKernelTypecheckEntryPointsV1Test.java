package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuKernelSourceHirV1;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GPU execution must remain denied through both ordinary and linked
 * typecheck admission, including after annotation expansion.
 * Source checks are not physical GPU evidence.
 */
final class GpuKernelTypecheckEntryPointsV1Test {
    private static final String GPU = """
            pub gpu fnc saxpy(
                f32 alpha, GpuReadBuffer<f32> x, GpuReadBuffer<f32> y,
                GpuWriteBuffer<f32> out, int n
            ): void {
                const int i = gpu.global_id(0);
                if i < n { out[i] = alpha * x[i] + y[i]; } fi
            }
            """;

    private static void unavailable(IllegalArgumentException e) {
        assertTrue(e.getMessage().contains("GPU-KERNEL-008"), e::getMessage);
        assertTrue(e.getMessage().contains("no implicit CPU fallback"), e::getMessage);
    }

    @Test void ordinaryTypecheckCannotAdmitGpuSourceKernel() {
        var program = Parser.parse(GPU);
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithDiagnostics(program));
        unavailable(error);
    }

    @Test void linkedMetadataTypecheckCannotSkipGpuKernelGate() {
        var program = Parser.parse(GPU);
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                        program, Map.of(), Map.of(), Map.of()));
        unavailable(error);
    }

    @Test void normalCompilerFrontendCannotDispatchGpuKernelOrFallBackToCpu() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(GPU));
        unavailable(error);
    }

    @Test void syntheticSecondModuleGpuDeclarationCannotHideFromNormalTypecheck() {
        var program = Parser.parse(GPU);
        var kernel = (Ast.GpuKernelDecl)
                program.modules().getFirst().declarations().getFirst();
        var source = new Ast.Program(List.of(
                new Ast.ModuleDecl("host", List.of()),
                new Ast.ModuleDecl("device", List.of(kernel))));
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithDiagnostics(source));
        unavailable(error);
    }

    @Test void syntheticSecondModuleGpuDeclarationCannotHideFromLinkedTypecheck() {
        var program = Parser.parse(GPU);
        var kernel = (Ast.GpuKernelDecl)
                program.modules().getFirst().declarations().getFirst();
        var source = new Ast.Program(List.of(
                new Ast.ModuleDecl("host", List.of()),
                new Ast.ModuleDecl("device", List.of(kernel))));
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                        source, Map.of(), Map.of(), Map.of()));
        unavailable(error);
    }

    @Test void sourceHirNeverCarriesDispatchAuthority() {
        var parsed = Parser.parse(GPU);
        var kernel = (Ast.GpuKernelDecl)
                parsed.modules().getFirst().declarations().getFirst();
        var source = GpuKernelSourceHirV1.lower(kernel);
        assertEquals(GpuKernelSourceHirV1.Evidence.TYPED_SOURCE_ONLY_NOT_EXECUTABLE,
                source.evidence());
        assertFalse(source.executable());
    }
}
